package io.ajentic.sre.schedule.cron;

public class InvalidCronException extends IllegalArgumentException {

    public InvalidCronException(String message) {
        super(message);
    }
}
