package io.ajentic.sre.schedule.store;

public class ScheduleNotFoundException extends RuntimeException {

    public ScheduleNotFoundException(String id) {
        super("Unknown schedule: " + id);
    }
}
