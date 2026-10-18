package io.ajentic.sre.schedule.store;

public class DuplicateScheduleException extends RuntimeException {

    public DuplicateScheduleException(String identity) {
        super("A schedule with the same identity already exists: " + identity);
    }
}
