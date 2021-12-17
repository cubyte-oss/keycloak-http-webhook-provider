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
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.net.http.HttpResponse.BodyHandlers.discarding;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.jboss.resteasy.util.HttpHeaderNames.CONTENT_TYPE;
import static org.jboss.resteasy.util.HttpHeaderNames.USER_AGENT;


public class KeycloakHttpWebhookProvider implements EventListenerProvider {

    private static final String WEBHOOK_ENV = "KEYCLOAK_WEBHOOK_URL";
    private static final String REPRESENTATION_FIELD = "representation";

    private static final String REALM_ID_HEADER = "X-Keycloak-RealmId";
    private static final String REALM_NAME_HEADER = "X-Keycloak-Realm";

    private static final Logger log = Logger.getLogger(KeycloakHttpWebhookProvider.class);
    private final HttpClient httpClient;
    private final URI webhookTarget;
    private final ObjectMapper mapper = new ObjectMapper();
    private final KeycloakSession keycloakSession;

    public KeycloakHttpWebhookProvider(KeycloakSession keycloakSession) {
        this.keycloakSession = keycloakSession;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(ALWAYS)
                .build();
        final String webhookEnvValue = System.getenv(WEBHOOK_ENV);
        try {
            this.webhookTarget = new URI(webhookEnvValue);
        } catch (URISyntaxException e) {
            log.error("Failed to parse webhook target as URI: " + webhookEnvValue, e);
            throw new RuntimeException(e);
        }
    }


    private void forwardWebhook(String realmId, PayloadSupplier jsonSupplier) {
        log.debug("Event occurred on realm " + realmId);
        if (webhookTarget == null) {
            log.error(WEBHOOK_ENV + " environment variable not configured, no events will be forwarded!");
            return;
        }
        RealmModel realm = keycloakSession.realms().getRealm(realmId);
        if (realm == null) {
            log.error("Failed to lookup realm " + realmId + "!");
            return;
        }

        String realmName = realm.getName();

        final HttpRequest request = HttpRequest.newBuilder()
                .uri(webhookTarget)
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .header(USER_AGENT, "Keycloak Webhook for " + realmName + " (" + realmId + ")")
                .header(REALM_ID_HEADER, realmId)
                .header(REALM_NAME_HEADER, realmName)
                .POST(new LazyPayloadPublisher(jsonSupplier))
                .build();

        httpClient.sendAsync(request, discarding()).whenComplete((response, ex) -> {
            if (ex != null) {
                log.error("The HTTP request to the webhook target failed!", ex);
                return;
            }

            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                log.error("The webhook returned an unsuccessful status code: " + statusCode);
            }

            log.info("Event successfully delivered!");
        });
    }

    @Override
    public void onEvent(Event event) {
        forwardWebhook(event.getRealmId(), () -> mapper.writeValueAsBytes(event));
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        forwardWebhook(event.getRealmId(), () -> {
            ObjectNode node = mapper.valueToTree(event);
            // An AdminEvent has weird JSON representation field which we need to special case.
            JsonNode representationNode = mapper.readTree(event.getRepresentation());
            node.replace(REPRESENTATION_FIELD, representationNode);
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

    private static class LazyPayloadPublisher implements Flow.Publisher<ByteBuffer>, HttpRequest.BodyPublisher {
        private final PayloadSupplier supplier;
        private final AtomicReference<Flow.Subscriber<? super ByteBuffer>> subscriber = new AtomicReference<>(null);

        public LazyPayloadPublisher(PayloadSupplier supplier) {
            this.supplier = supplier;
        }

        @Override
        public long contentLength() {
            return -1;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            if (this.subscriber.compareAndSet(null, subscriber)) {
                subscriber.onSubscribe(new LazyPayloadSubscription(subscriber));
            } else {
                subscriber.onError(new IllegalStateException("publisher already subscribed!"));
            }
        }

        private class LazyPayloadSubscription implements Flow.Subscription {
            private final AtomicBoolean done;
            private final Flow.Subscriber<? super ByteBuffer> subscriber;

            public LazyPayloadSubscription(Flow.Subscriber<? super ByteBuffer> subscriber) {
                this.subscriber = subscriber;
                this.done = new AtomicBoolean(false);
            }

            @Override
            public void request(long n) {
                if (done.compareAndSet(false, true)) {
                    if (n < 0) {
                        subscriber.onError(new IllegalArgumentException("demand may not be negative!"));
                    } else {
                        try {
                            byte[] payload = supplier.get();
                            if (log.isDebugEnabled()) {
                                log.debug("Event payload: " + new String(payload, UTF_8));
                            }

                            subscriber.onNext(ByteBuffer.wrap(payload));
                            subscriber.onComplete();
                        } catch (IOException e) {
                            subscriber.onError(e);
                        }
                    }
                }
            }

            @Override
            public void cancel() {
                done.set(false);
            }
        }
    }
}
