package org.archlinux.keycloakhttpwebhookprovider.provider;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


public class KeycloakHttpWebhookProvider implements EventListenerProvider {

    private static final String WEBHOOK_ENV = "KEYCLOAK_WEBHOOK_URL";
    private static final Logger log = Logger.getLogger(KeycloakHttpWebhookProvider.class);
    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient httpClient = new OkHttpClient();
    private final String serverUrl = System.getenv(WEBHOOK_ENV);
    private final ObjectMapper mapper = new ObjectMapper();


    private void sendJson(String jsonString, String realmId) {
        if (serverUrl == null) {
            log.error(WEBHOOK_ENV + " environment variable not configured, no events will be forwarded!");
            return;
        }
        Request request = new Request.Builder()
                .url(this.serverUrl)
                .addHeader("User-Agent", "Keycloak Webhook")
                .addHeader("X-Keycloak-Realm", realmId)
                .post(RequestBody.create(jsonString, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error(String.format("Failed to POST webhook: %s %s",  response.code(), response.message()));
            }
        } catch (IOException e) {
            log.error("Failed to POST webhook:", e);
        }
    }

    @Override
    public void onEvent(Event event) {
        log.debug("Event Occurred:" + toString(event));
    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean b) {
        log.debug("Admin Event Occurred:" + toString(adminEvent));
    }

    @Override
    public void close() {}

    private String toString(Event event) {
        String jsonString = "";
        try {
            jsonString = mapper.writeValueAsString(event);
            sendJson(jsonString, event.getRealmId());
        } catch (IOException e) {
            log.error("Failed to send event to webhook!", e);
        }
        return jsonString;
    }

    private String toString(AdminEvent adminEvent) {
        String jsonString = "";
        try {
            // An AdminEvent has weird JSON representation field which we need to special case.
            JsonNode representationNode = mapper.readTree(adminEvent.getRepresentation());
            ObjectNode node = mapper.valueToTree(adminEvent);
            node.replace("representation", representationNode);
            jsonString = mapper.writeValueAsString(node);

            sendJson(jsonString, adminEvent.getRealmId());
        } catch (IOException e) {
            log.error("Failed to send admin event to webhook!", e);
        }
        return jsonString;
    }
}
