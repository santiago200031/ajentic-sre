package io.ajentic.sre.schedule.engine;

import java.time.Instant;
import java.util.List;

public record TickReport(Instant tickedAt, List<String> executed, List<String> deferred, int recoveryBacklog) {

    public TickReport {
        executed = List.copyOf(executed);
        deferred = List.copyOf(deferred);
    }
}
