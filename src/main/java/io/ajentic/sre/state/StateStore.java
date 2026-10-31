package io.ajentic.sre.state;

import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.schedule.store.ScheduleDefinition;

import java.util.List;

/**
 * Persistence seam for durable application state (schedules and incident drills).
 */
public interface StateStore {

    List<ScheduleDefinition> loadSchedules();

    void saveSchedules(List<ScheduleDefinition> schedules);

    List<EpisodeResult> loadDrills();

    void saveDrills(List<EpisodeResult> drills);
}
