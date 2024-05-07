package net.cubyte.keycloak.httpwebhookprovider.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;

import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.net.http.HttpClient.Version.HTTP_2;


public class KeycloakHttpWebhookProviderFactory implements EventListenerProviderFactory {
    private static final Logger logger = Logger.getLogger(KeycloakHttpWebhookProviderFactory.class);

    static final String WEBHOOK_ENV = "KEYCLOAK_WEBHOOK_URL";
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(1);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECTION_TIMEOUT)
            .version(HTTP_2)
            .followRedirects(ALWAYS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI webhookTarget;

    public KeycloakHttpWebhookProviderFactory() {
        final String webhookEnvValue = System.getenv(WEBHOOK_ENV);
        if (webhookEnvValue == null) {
            throw new IllegalArgumentException("No webhook URL has been given! Set the " + WEBHOOK_ENV + " env var!");
        }
        try {
            this.webhookTarget = new URI(webhookEnvValue);
        } catch (URISyntaxException e) {
            logger.error("Failed to parse webhook target as URI: " + webhookEnvValue, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public EventListenerProvider create(KeycloakSession keycloakSession) {
        return new KeycloakHttpWebhookProvider(keycloakSession, httpClient, mapper, webhookTarget);
    }

    @Override
    public void init(Config.Scope config_scope) {
    }

    @Override
    public void postInit(KeycloakSessionFactory keycloakSessionFactory) {

    }

    @Override
    public void close() {

    }

    @Override
    public String getId() {
        return "http_webhook";
    }
}
