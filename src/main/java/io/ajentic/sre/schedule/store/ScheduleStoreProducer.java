package io.ajentic.sre.schedule.store;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Locale;

@ApplicationScoped
public class ScheduleStoreProducer {

    @Produces
    @ApplicationScoped
    public ScheduleStore scheduleStore(
            @ConfigProperty(name = "ajentic.state.store", defaultValue = "file") String storeType,
            InMemoryScheduleStore inMemoryStore,
            FileScheduleStore fileStore) {
        return switch (storeType == null ? "file" : storeType.trim().toLowerCase(Locale.ROOT)) {
            case "memory" -> inMemoryStore;
            case "file" -> fileStore;
            case "jdbc" -> throw new UnsupportedOperationException("JDBC schedule store is not implemented yet.");
            case "redis" -> throw new UnsupportedOperationException("Redis schedule store is not implemented yet.");
            default -> throw new IllegalArgumentException("Unknown ajentic.state.store: " + storeType);
        };
    }
}
