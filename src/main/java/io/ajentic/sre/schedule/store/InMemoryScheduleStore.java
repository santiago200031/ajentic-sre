package io.ajentic.sre.schedule.store;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@DefaultBean
@ApplicationScoped
public class InMemoryScheduleStore implements ScheduleStore {

    private final Map<String, ScheduleDefinition> schedules = new LinkedHashMap<>();
    private final Map<String, String> idByIdentity = new HashMap<>();
    private final Map<String, String> idByName = new HashMap<>();

    @Override
    public synchronized List<ScheduleDefinition> all() {
        return List.copyOf(schedules.values());
    }

    @Override
    public synchronized Optional<ScheduleDefinition> byId(String id) {
        return Optional.ofNullable(schedules.get(id));
    }

    @Override
    public synchronized Optional<ScheduleDefinition> byName(String name) {
        String id = idByName.get(name);
        return id == null ? Optional.empty() : Optional.ofNullable(schedules.get(id));
    }

    @Override
    public synchronized ScheduleDefinition add(ScheduleDefinition definition) {
        String identity = definition.identity();
        if (idByIdentity.containsKey(identity)) {
            throw new DuplicateScheduleException(identity);
        }
        String id = blank(definition.id()) ? UUID.randomUUID().toString() : definition.id();
        String loopGroupId = blank(definition.loopGroupId()) ? UUID.randomUUID().toString() : definition.loopGroupId();
        ScheduleDefinition stored = new ScheduleDefinition(
                id,
                definition.name(),
                definition.cron(),
                true,
                false,
                1L,
                loopGroupId,
                definition.zone(),
                definition.scenarioId());
        schedules.put(id, stored);
        idByIdentity.put(identity, id);
        idByName.putIfAbsent(stored.name(), id);
        return stored;
    }

    @Override
    public synchronized ScheduleDefinition update(
            String id,
            long expectedVersion,
            Function<ScheduleDefinition, ScheduleDefinition> change) {
        ScheduleDefinition current = require(id);
        if (current.version() != expectedVersion) {
            throw new StaleScheduleException(id, expectedVersion, current.version());
        }
        ScheduleDefinition changed = change.apply(current);
        ScheduleDefinition candidate = new ScheduleDefinition(
                id,
                changed.name(),
                changed.cron(),
                changed.enabled(),
                false,
                current.version() + 1,
                current.loopGroupId(),
                changed.zone(),
                changed.scenarioId());
        String previousIdentity = current.identity();
        String nextIdentity = candidate.identity();
        if (!previousIdentity.equals(nextIdentity) && idByIdentity.containsKey(nextIdentity)) {
            throw new DuplicateScheduleException(nextIdentity);
        }
        idByIdentity.remove(previousIdentity);
        idByIdentity.put(nextIdentity, id);
        schedules.put(id, candidate);
        if (!current.name().equals(candidate.name())) {
            releaseName(current.name(), id);
            idByName.putIfAbsent(candidate.name(), id);
        }
        return candidate;
    }

    @Override
    public synchronized void stop(String id) {
        ScheduleDefinition current = schedules.remove(id);
        if (current != null) {
            idByIdentity.remove(current.identity());
            releaseName(current.name(), id);
        }
    }

    private void releaseName(String name, String id) {
        if (id.equals(idByName.get(name))) {
            idByName.remove(name);
            schedules.values().stream()
                    .filter(schedule -> schedule.name().equals(name))
                    .findFirst()
                    .ifPresent(schedule -> idByName.put(name, schedule.id()));
        }
    }

    private ScheduleDefinition require(String id) {
        ScheduleDefinition definition = schedules.get(id);
        if (definition == null) {
            throw new ScheduleNotFoundException(id);
        }
        return definition;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
