package io.ajentic.sre.rl;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;
import java.util.Locale;

public enum TelemetrySignal {
    METRICS,
    LOGS,
    TRACES,
    DEPLOYS;

    /** Case-insensitive wire parsing, shared by the REST mapper, the built-in tools and Jackson. */
    @JsonCreator
    public static TelemetrySignal fromWireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Telemetry signal is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(signal -> signal.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown telemetry signal: " + value));
    }
}
