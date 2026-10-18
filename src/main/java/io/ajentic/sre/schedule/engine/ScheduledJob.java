package io.ajentic.sre.schedule.engine;

@FunctionalInterface
public interface ScheduledJob {

    void run(ScheduleContext context);
}
