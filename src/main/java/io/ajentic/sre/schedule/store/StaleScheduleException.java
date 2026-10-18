package io.ajentic.sre.schedule.store;

public class StaleScheduleException extends RuntimeException {

    public StaleScheduleException(String id, long expectedVersion, long actualVersion) {
        super("Stale write for schedule %s: expected version %d but found %d"
                .formatted(id, expectedVersion, actualVersion));
    }
}
