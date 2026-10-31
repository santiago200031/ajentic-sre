package io.ajentic.sre.simulation.action;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;
import java.util.Locale;

public enum RemediationKind {
    RESTART("Restart the affected workload"),
    SCALE_UP("Scale out the affected workload or its dependencies"),
    ROLLBACK_DEPLOY("Roll back the most recent deployment"),
    FIX_CONFIG("Apply a configuration or credential fix"),
    FAILOVER("Fail over to a healthy replica or zone"),
    EXPAND_DISK("Expand the volume of the affected node");

    private final String description;

    RemediationKind(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /** Case-insensitive wire parsing, shared by the REST mapper, the built-in tools and Jackson. */
    @JsonCreator
    public static RemediationKind fromWireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Remediation kind is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(kind -> kind.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown remediation kind: " + value));
    }
}
