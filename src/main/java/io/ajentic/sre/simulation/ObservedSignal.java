package io.ajentic.sre.simulation;

import java.util.Map;

public record ObservedSignal(
        TelemetrySignal signal,
        String service,
        String summary,
        Map<String, Object> attributes) {

    public ObservedSignal {
        attributes = Map.copyOf(attributes);
    }
}
