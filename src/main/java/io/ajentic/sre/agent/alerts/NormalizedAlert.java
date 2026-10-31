package io.ajentic.sre.agent.alerts;

/**
 * Standardized alert representation across diverse monitoring providers (CloudWatch, Azure Monitor,
 * Alertmanager, and direct JSON).
 */
public record NormalizedAlert(
        AlertSeverity severity,
        String service,
        String repository,
        String branch,
        String title,
        String message,
        String stackTrace,
        String fingerprint) {

    public NormalizedAlert {
        severity = severity == null ? AlertSeverity.INFO : severity;
        branch = branch == null || branch.isBlank() ? "main" : branch;
        fingerprint = fingerprint == null || fingerprint.isBlank() ? "unknown" : fingerprint;
    }
}
