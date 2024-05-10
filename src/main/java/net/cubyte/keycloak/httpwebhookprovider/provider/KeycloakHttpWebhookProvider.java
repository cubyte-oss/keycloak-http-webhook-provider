package net.cubyte.keycloak.httpwebhookprovider.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

import static jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static jakarta.ws.rs.core.HttpHeaders.USER_AGENT;
import static java.net.http.HttpResponse.BodyHandlers.discarding;
import static net.cubyte.keycloak.httpwebhookprovider.provider.KeycloakHttpWebhookProviderFactory.WEBHOOK_ENV;

public class KeycloakHttpWebhookProvider implements EventListenerProvider {

    private static final String REPRESENTATION_FIELD = "representation";

    private static final String REALM_ID_HEADER = "X-Keycloak-RealmId";
    private static final String REALM_NAME_HEADER = "X-Keycloak-Realm";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger logger = Logger.getLogger(KeycloakHttpWebhookProvider.class);

    private final HttpClient httpClient;
    private final URI webhookTarget;
    private final ObjectMapper mapper;
    private final KeycloakSession keycloakSession;

    public KeycloakHttpWebhookProvider(KeycloakSession keycloakSession, HttpClient httpClient, ObjectMapper mapper, URI webhookTarget) {
        this.keycloakSession = keycloakSession;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.webhookTarget = webhookTarget;
    }

    private void forwardWebhook(String realmId, String type, PayloadSupplier jsonSupplier) {
        logger.debug("Event occurred on realm " + realmId);
        if (webhookTarget == null) {
            logger.error(WEBHOOK_ENV + " environment variable not configured, no events will be forwarded!");
            return;
        }
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

        final HttpRequest request = HttpRequest.newBuilder()
                .uri(webhookTarget)
                .header(CONTENT_TYPE, "application/json")
                .header(USER_AGENT, "Keycloak Webhook for " + realmName + " (" + realmId + ")")
                .header(REALM_ID_HEADER, realmId)
                .header(REALM_NAME_HEADER, realmName)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        httpClient.sendAsync(request, discarding()).whenComplete((response, ex) -> {
            if (ex != null) {
                logger.error("The HTTP request to the webhook target (" +  webhookTarget + ") failed!", ex);
                return;
            }

            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                logger.error("The webhook returned an unsuccessful status code: " + statusCode);
            }

            logger.info("Event of type " + type + " successfully delivered!");
        });
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
