package net.cubyte.keycloak.httpwebhookprovider.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.cubyte.keycloak.httpwebhookprovider.provider.KeycloakHttpWebhookConfiguration.WebhookTarget;
import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;

import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;
import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static jakarta.ws.rs.core.HttpHeaders.USER_AGENT;
import static java.net.http.HttpResponse.BodyHandlers.discarding;

public class KeycloakHttpWebhookProvider implements EventListenerProvider {
    private static final String REPRESENTATION_FIELD = "representation";

    private static final String REALM_ID_HEADER = "X-Keycloak-RealmId";
    private static final String REALM_NAME_HEADER = "X-Keycloak-Realm";


    private static final Logger logger = Logger.getLogger(KeycloakHttpWebhookProvider.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final KeycloakSession keycloakSession;
    private final KeycloakHttpWebhookConfiguration config;

    public KeycloakHttpWebhookProvider(KeycloakSession keycloakSession, HttpClient httpClient, ObjectMapper mapper, KeycloakHttpWebhookConfiguration config) {
        this.keycloakSession = keycloakSession;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.config = config;
    }

    private void forwardWebhook(String realmId, String type, PayloadSupplier jsonSupplier) {
        logger.debug("Event occurred on realm " + realmId);
        RealmModel realm = keycloakSession.realms().getRealm(realmId);
        if (realm == null) {
            logger.error("Failed to lookup realm " + realmId + "!");
            return;
        }

        String realmName = realm.getName();
        final byte[] body;
        try {
            body = jsonSupplier.get();
        } catch (IOException e) {
            logger.error("Failed to produce json from event!", e);
            return;
        }

        final List<WebhookTarget> targets = config.getTargetsForRealm(realmName);
        for (WebhookTarget target : targets) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(target.getUrl())
                    .header(CONTENT_TYPE, "application/json")
                    .header(USER_AGENT, "Keycloak Webhook for " + realmName + " (" + realmId + ")")
                    .header(REALM_ID_HEADER, realmId)
                    .header(REALM_NAME_HEADER, realmName)
                    .timeout(target.getRequestTimeoutMillis())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            String authorizationHeader = target.getAuthorizationHeader();
            if (authorizationHeader != null) {
                requestBuilder = requestBuilder.header(AUTHORIZATION, authorizationHeader);
            }

            httpClient.sendAsync(requestBuilder.build(), discarding()).whenComplete((response, ex) -> {
                if (ex != null) {
                    logger.error("The HTTP request to the webhook target (" +  target.getUrl() + ") failed!", ex);
                    return;
                }

                int statusCode = response.statusCode();
                if (statusCode < 200 || statusCode >= 300) {
                    logger.error("The webhook returned an unsuccessful status code: " + statusCode);
                }

                logger.info("Event of type " + type + " successfully delivered!");
            });
        }
    }

    @Override
    public void onEvent(Event event) {
        forwardWebhook(event.getRealmId(), event.getType().toString(), () -> mapper.writeValueAsBytes(event));
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        forwardWebhook(event.getRealmId(), event.getResourceType() + ":" + event.getOperationType(), () -> {
            ObjectNode node = mapper.valueToTree(event);
            // An AdminEvent has weird JSON representation field which we need to special case.
            String representation = event.getRepresentation();
            if (representation != null) {
                JsonNode representationNode = mapper.readTree(representation);
                node.replace(REPRESENTATION_FIELD, representationNode);
            }
            return mapper.writeValueAsBytes(event);
        });
    }

    @Override
    public void close() {
    }

    @FunctionalInterface
    private interface PayloadSupplier {
        byte[] get() throws IOException;
    }
}
