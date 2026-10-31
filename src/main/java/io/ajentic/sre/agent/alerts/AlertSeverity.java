package io.ajentic.sre.agent.alerts;

import java.util.Locale;

/**
 * Multi-cloud alert severity levels: INFO < WARNING < ERROR < CRITICAL.
 */
public enum AlertSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL;

    public boolean isAtLeast(AlertSeverity min) {
        return this.compareTo(min) >= 0;
    }

    public static AlertSeverity from(Object raw) {
        if (raw == null) {
            return INFO;
        }
        String value = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "CRITICAL", "CRIT", "FATAL" -> CRITICAL;
            case "ERROR", "ERR", "SEV0", "SEV1", "SEV2", "P1", "P2", "P3", "ALARM" -> ERROR;
            case "WARNING", "WARN", "SEV3", "P4" -> WARNING;
            case "INFO", "INFORMATIONAL", "SEV4", "SEV5", "P5", "OK" -> INFO;
            default -> INFO;
        };
    }
}
