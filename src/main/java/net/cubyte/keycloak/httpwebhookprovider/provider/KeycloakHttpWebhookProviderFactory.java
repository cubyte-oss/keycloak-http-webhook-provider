package net.cubyte.keycloak.httpwebhookprovider.provider;

import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;


public class KeycloakHttpWebhookProviderFactory implements EventListenerProviderFactory {

    @Override
    public EventListenerProvider create(KeycloakSession keycloakSession) {
        return new KeycloakHttpWebhookProvider(keycloakSession);
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
