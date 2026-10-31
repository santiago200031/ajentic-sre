package io.ajentic.sre.state;

import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.schedule.store.ScheduleDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.util.List;

/**
 * Seam for future JDBC/PostgreSQL-backed state storage.
 */
@ApplicationScoped
@Typed(JdbcStateStore.class)
public class JdbcStateStore implements StateStore {

    @Override
    public List<ScheduleDefinition> loadSchedules() {
        throw new UnsupportedOperationException("JDBC state store is not implemented yet.");
    }

    @Override
    public void saveSchedules(List<ScheduleDefinition> schedules) {
        throw new UnsupportedOperationException("JDBC state store is not implemented yet.");
    }

    @Override
    public List<EpisodeResult> loadDrills() {
        throw new UnsupportedOperationException("JDBC state store is not implemented yet.");
    }

    @Override
    public void saveDrills(List<EpisodeResult> drills) {
        throw new UnsupportedOperationException("JDBC state store is not implemented yet.");
    }
}
