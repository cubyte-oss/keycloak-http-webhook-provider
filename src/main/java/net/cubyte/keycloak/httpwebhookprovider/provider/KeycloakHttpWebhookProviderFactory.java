package net.cubyte.keycloak.httpwebhookprovider.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.net.http.HttpClient.Version.HTTP_2;

public class KeycloakHttpWebhookProviderFactory implements EventListenerProviderFactory {
    private static final Logger logger = Logger.getLogger(KeycloakHttpWebhookProviderFactory.class);

    static final String WEBHOOK_CONFIG_ENV = "KEYCLOAK_WEBHOOK_CONFIG_FILE";
    static final String WEBHOOK_CONFIG_WATCH_ENV = "KEYCLOAK_WEBHOOK_CONFIG_WATCH";
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(1);

    private HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECTION_TIMEOUT)
            .version(HTTP_2)
            .followRedirects(ALWAYS)
            .build();
    private ObjectMapper mapper;
    private KeycloakHttpWebhookConfiguration configuration;

    @Override
    public String getId() {
        return "http_webhook";
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        final String webhookConfigEnvValue = System.getenv(WEBHOOK_CONFIG_ENV);
        if (webhookConfigEnvValue == null) {
            throw new IllegalArgumentException("No webhook config file has been given! Set the " + WEBHOOK_CONFIG_ENV + " env var!");
        }
        final boolean watchConfig = Objects.equals(System.getenv(WEBHOOK_CONFIG_WATCH_ENV), "true");
        httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECTION_TIMEOUT)
                .version(HTTP_2)
                .followRedirects(ALWAYS)
                .build();
        mapper = new ObjectMapper();
        configuration = new KeycloakHttpWebhookConfiguration(mapper, Path.of(webhookConfigEnvValue), watchConfig);
    }

    @Override
    public EventListenerProvider create(KeycloakSession keycloakSession) {
        return new KeycloakHttpWebhookProvider(keycloakSession, httpClient, mapper, configuration);
    }

    @Override
    public void close() {
        httpClient = null;
        mapper = null;
        configuration.close();
        configuration = null;
    }
}
