package io.ajentic.sre.schedule.store;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface ScheduleStore {

    List<ScheduleDefinition> all();

    Optional<ScheduleDefinition> byId(String id);

    Optional<ScheduleDefinition> byName(String name);

    ScheduleDefinition add(ScheduleDefinition definition);

    ScheduleDefinition update(String id, long expectedVersion, Function<ScheduleDefinition, ScheduleDefinition> change);

    void stop(String id);
}
