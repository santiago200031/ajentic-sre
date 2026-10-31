package io.ajentic.sre.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Locale;

@ApplicationScoped
public class StateStoreProducer {

    @Produces
    @ApplicationScoped
    public StateStore stateStore(
            @ConfigProperty(name = "ajentic.state.store", defaultValue = "file") String backend,
            InMemoryStateStore memoryStore,
            FileStateStore fileStore,
            JdbcStateStore jdbcStore,
            RedisStateStore redisStore) {
        return switch (backend == null ? "file" : backend.trim().toLowerCase(Locale.ROOT)) {
            case "memory" -> memoryStore;
            case "file" -> fileStore;
            case "jdbc" -> jdbcStore;
            case "redis" -> redisStore;
            default -> throw new IllegalArgumentException("Unknown ajentic.state.store: " + backend);
        };
    }
}
