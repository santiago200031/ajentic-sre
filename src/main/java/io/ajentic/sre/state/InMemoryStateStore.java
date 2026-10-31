package io.ajentic.sre.state;

import io.ajentic.sre.simulation.drill.SimulationResult;
import io.ajentic.sre.schedule.store.ScheduleDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ephemeral in-memory state store for tests and offline runs.
 */
@ApplicationScoped
@Typed(InMemoryStateStore.class)
public class InMemoryStateStore implements StateStore {

    private final List<ScheduleDefinition> schedules = new CopyOnWriteArrayList<>();
    private final List<SimulationResult> drills = new CopyOnWriteArrayList<>();

    @Override
    public List<ScheduleDefinition> loadSchedules() {
        return List.copyOf(schedules);
    }

    @Override
    public void saveSchedules(List<ScheduleDefinition> schedules) {
        this.schedules.clear();
        if (schedules != null) {
            this.schedules.addAll(schedules);
        }
    }

    @Override
    public List<SimulationResult> loadDrills() {
        return List.copyOf(drills);
    }

    @Override
    public void saveDrills(List<SimulationResult> drills) {
        this.drills.clear();
        if (drills != null) {
            this.drills.addAll(drills);
        }
    }
}
