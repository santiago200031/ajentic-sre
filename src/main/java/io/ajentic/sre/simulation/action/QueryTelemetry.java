package io.ajentic.sre.simulation.action;

import io.ajentic.sre.simulation.TelemetrySignal;
import java.util.Objects;

public record QueryTelemetry(TelemetrySignal signal, String service) implements AgentAction {

    public QueryTelemetry {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(service, "service");
    }

    @Override
    public String describe() {
        return "query " + signal + " for " + service;
    }
}
