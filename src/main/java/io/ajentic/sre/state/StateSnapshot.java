package io.ajentic.sre.state;

import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.schedule.store.ScheduleDefinition;

import java.util.List;

/**
 * Top-level document stored in {@code .ajentic/state.json}. Includes a schema version field for migrations.
 */
public record StateSnapshot(
        int schemaVersion,
        List<ScheduleDefinition> schedules,
        List<EpisodeResult> drills) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public StateSnapshot {
        schedules = schedules == null ? List.of() : List.copyOf(schedules);
        drills = drills == null ? List.of() : List.copyOf(drills);
    }

    public static StateSnapshot empty() {
        return new StateSnapshot(CURRENT_SCHEMA_VERSION, List.of(), List.of());
    }
}
