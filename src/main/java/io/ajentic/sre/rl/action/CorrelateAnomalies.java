package io.ajentic.sre.rl.action;

import java.util.List;
import java.util.Objects;

public record CorrelateAnomalies(List<String> services, int window) implements AgentAction {

    public CorrelateAnomalies {
        Objects.requireNonNull(services, "services");
        services = List.copyOf(services);
        if (window < 0) {
            throw new IllegalArgumentException("window must be non-negative");
        }
    }

    @Override
    public String describe() {
        return "correlate anomalies across " + (services.isEmpty() ? "the alerting service" : services)
                + " within " + window + " hops";
    }
}
