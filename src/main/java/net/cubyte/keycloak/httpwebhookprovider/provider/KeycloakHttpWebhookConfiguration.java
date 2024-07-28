package net.cubyte.keycloak.httpwebhookprovider.provider;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

public final class KeycloakHttpWebhookConfiguration implements Closeable {
    private static final Logger logger = Logger.getLogger(KeycloakHttpWebhookConfiguration.class);
    private static final TypeReference<Config> CONFIG_TYPE_REF = new TypeReference<>() {};

    private volatile Config config;
    private final ObjectMapper mapper;
    private final ConcurrentMap<String, List<WebhookTarget>> routeCache = new ConcurrentHashMap<>();
    private final Thread configWatcher;

    KeycloakHttpWebhookConfiguration(ObjectMapper mapper, Path configFile, boolean watch) {
        this.mapper = mapper;
        try {
            loadConfig(mapper, configFile);
        } catch (Exception e) {
            logger.error("Failed to load webhook configuration!", e);
            throw new RuntimeException("Failed to load configuration!", e);
        }
        if (watch) {
            configWatcher = setupConfigWatcher(configFile);
        } else {
            configWatcher = null;
        }
    }

    public List<WebhookTarget> getTargetsForRealm(String realm) {
        return routeCache.computeIfAbsent(realm, (key) -> {
            final Config currentConfig = config;
            Set<String> targetNames = currentConfig.routes.get(key);
            if (targetNames == null || targetNames.isEmpty()) {
                targetNames = currentConfig.defaultTargets;
            }
            List<WebhookTarget> targets = new ArrayList<>(targetNames.size());
            for (String targetName : targetNames) {
                // config validation ensures targets will never be null
                targets.add(currentConfig.targets.get(targetName));
            }
            return targets;
        });
    }

    private Thread setupConfigWatcher(Path configFile) {
        try {
            Thread watcher = new Thread(watchConfig(configFile));
            watcher.setDaemon(true);
            watcher.start();
            return watcher;
        } catch (IOException e) {
            logger.warn("Failed to setup watcher for webhook config, no automatic reloads will happen!", e);
        }
        return null;
    }

    private Runnable watchConfig(Path configFile) throws IOException {
        Path configDir = configFile.getParent();
        final WatchService watchService = configDir.getFileSystem().newWatchService();
        final WatchKey key = configDir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        return () -> {
            final Thread me = Thread.currentThread();
            while (!me.isInterrupted() && key.isValid()) {
                try {
                    final WatchKey polledKey = watchService.take();
                    if (polledKey == key && key.isValid()) {
                        final List<WatchEvent<?>> events = polledKey.pollEvents();
                        polledKey.reset();
                        if (wasFileChanged(configFile, configDir, events)) {
                            loadConfig(mapper, configFile);
                        }
                    }
                } catch (ClosedWatchServiceException e) {
                    break;
                } catch (Exception e) {
                    logger.warn("Failed to reload the configuration after it changed!", e);
                }
            }
            key.cancel();
            try {
                watchService.close();
            } catch (IOException e) {
                logger.warn("Failed to close WatchService after watch thread completed!", e);
            }
            logger.info("Config file watcher terminated.");
        };
    }

    private static boolean safeIsSameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean wasFileChanged(Path file, Path watchDir, List<WatchEvent<?>> events) {
        for (WatchEvent<?> event : events) {
            final Object context = event.context();
            if (!(context instanceof Path)) {
                continue;
            }
            if (safeIsSameFile(file, watchDir.resolve((Path) context))) {
                return true;
            }
        }
        return false;
    }

    private void loadConfig(ObjectMapper mapper, Path configFile) throws IOException {
        Config newConfig;
        try (Reader r = Files.newBufferedReader(configFile)) {
            newConfig = mapper.readValue(r, CONFIG_TYPE_REF);
        }
        validateConfig(newConfig);
        config = newConfig;
        routeCache.clear();
        logger.info("Config loaded!");
    }

    private void validateConfig(Config config) {
        if (config.defaultTargets.isEmpty() && config.targets.isEmpty()) {
            throw new IllegalStateException("No routes have been defined!");
        }
        for (String defaultTarget : config.defaultTargets) {
            if (!config.targets.containsKey(defaultTarget)) {
                throw new IllegalStateException("Default target " + defaultTarget + " is not defined!");
            }
        }
        for (Map.Entry<String, Set<String>> route : config.routes.entrySet()) {
            for (String target : route.getValue()) {
                if (!config.targets.containsKey(target)) {
                    throw new IllegalStateException("Route for realm " + route.getKey() + " references undefined target " + target + "!");
                }
            }
        }
    }

    @Override
    public void close() {
        if (configWatcher != null) {
            configWatcher.interrupt();
            try {
                configWatcher.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class Config {
        public final Map<String, WebhookTarget> targets;
        public final Set<String> defaultTargets;
        public final Map<String, Set<String>> routes;

        @JsonCreator
        public Config(@JsonProperty(value = "targets", required = true) Map<String, WebhookTarget> targets,
                      @JsonProperty(value = "defaultTargets") Set<String> defaultTargets,
                      @JsonProperty(value = "routes") Map<String, Set<String>> routes) {
            this.targets = targets;
            this.defaultTargets = Objects.requireNonNullElseGet(defaultTargets, Collections::emptySet);
            this.routes = Objects.requireNonNullElseGet(routes, Collections::emptyMap);
        }
    }

    public static final class WebhookTarget {
        private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

        private final URI url;
        private final String authorizationHeader;
        private final Duration requestTimeoutMillis;

        @JsonCreator
        public WebhookTarget(@JsonProperty(value = "url", required = true) URI url,
                             @JsonProperty(value = "authorizationHeader") String authorizationHeader,
                             @JsonProperty(value = "requestTimeoutMillis") Long requestTimeoutMillis) {
            this.url = url;
            this.authorizationHeader = authorizationHeader;
            if (requestTimeoutMillis == null) {
                this.requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT;
            } else {
                this.requestTimeoutMillis = Duration.ofMillis(requestTimeoutMillis);
            }
        }

        public URI getUrl() {
            return url;
        }

        public String getAuthorizationHeader() {
            return authorizationHeader;
        }

        public Duration getRequestTimeoutMillis() {
            return requestTimeoutMillis;
        }
    }
}
