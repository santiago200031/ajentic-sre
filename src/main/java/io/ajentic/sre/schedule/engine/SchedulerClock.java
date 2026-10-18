package io.ajentic.sre.schedule.engine;

import java.time.Instant;

@FunctionalInterface
public interface SchedulerClock {

    Instant now();
}
