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

    private URI webhookTarget;
    private HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECTION_TIMEOUT)
            .version(HTTP_2)
            .followRedirects(ALWAYS)
            .build();
    private ObjectMapper mapper;

    @Override
    public String getId() {
        return "http_webhook";
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        final String webhookEnvValue = System.getenv(WEBHOOK_ENV);
        if (webhookEnvValue == null) {
            throw new IllegalArgumentException("No webhook URL has been given! Set the " + WEBHOOK_ENV + " env var!");
        }
        try {
            webhookTarget = new URI(webhookEnvValue);
        } catch (URISyntaxException e) {
            logger.error("Failed to parse webhook target as URI: " + webhookEnvValue, e);
            throw new RuntimeException(e);
        }

        httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECTION_TIMEOUT)
                .version(HTTP_2)
                .followRedirects(ALWAYS)
                .build();
        mapper = new ObjectMapper();
    }

    @Override
    public EventListenerProvider create(KeycloakSession keycloakSession) {
        return new KeycloakHttpWebhookProvider(keycloakSession, httpClient, mapper, webhookTarget);
    }

    @Override
    public void close() {
        webhookTarget = null;
        httpClient = null;
        mapper = null;
    }
}
