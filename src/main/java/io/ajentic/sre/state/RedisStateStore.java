package io.ajentic.sre.state;

import io.ajentic.sre.simulation.drill.SimulationResult;
import io.ajentic.sre.schedule.store.ScheduleDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.util.List;

/**
 * Seam for future Redis-backed state storage.
 */
@ApplicationScoped
@Typed(RedisStateStore.class)
public class RedisStateStore implements StateStore {

    @Override
    public List<ScheduleDefinition> loadSchedules() {
        throw new UnsupportedOperationException("Redis state store is not implemented yet.");
    }

    @Override
    public void saveSchedules(List<ScheduleDefinition> schedules) {
        throw new UnsupportedOperationException("Redis state store is not implemented yet.");
    }

    @Override
    public List<SimulationResult> loadDrills() {
        throw new UnsupportedOperationException("Redis state store is not implemented yet.");
    }

    @Override
    public void saveDrills(List<SimulationResult> drills) {
        throw new UnsupportedOperationException("Redis state store is not implemented yet.");
    }
}
