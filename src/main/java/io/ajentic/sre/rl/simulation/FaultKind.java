package io.ajentic.sre.rl.simulation;

import io.ajentic.sre.rl.action.RemediationKind;

import java.util.List;

public enum FaultKind {

    DB_POOL_EXHAUSTION(
            RemediationKind.SCALE_UP,
            "database connection pool exhaustion",
            "connections waiting on the database pool",
            "checkout requests are timing out while waiting for a database connection",
            List.of(
                    "HikariPool-1 - Connection is not available, request timed out after 30000ms",
                    "too many connections for role \"checkout\"; pool at capacity"),
            0.06, 70.0),

    MEMORY_LEAK(
            RemediationKind.RESTART,
            "application memory leak",
            "heap usage climbing toward OOM",
            "the worker heap keeps growing and the process is being restarted by the probe",
            List.of(
                    "java.lang.OutOfMemoryError: Java heap space",
                    "GC overhead limit exceeded; heap usage above 92%"),
            0.03, 0.0),

    CERTIFICATE_EXPIRY(
            RemediationKind.FIX_CONFIG,
            "expired TLS certificate",
            "TLS handshake failures",
            "clients receive 503 because the serving certificate expired",
            List.of(
                    "x509: certificate has expired or is not yet valid",
                    "TLS handshake failed: certificate verify failed"),
            0.14, 0.0),

    DNS_FAILURE(
            RemediationKind.FAILOVER,
            "cluster DNS resolution failure",
            "name resolution errors",
            "downstream calls fail because service names can no longer be resolved",
            List.of(
                    "lookup search-index on 10.96.0.10:53: no such host",
                    "SERVFAIL resolving search-db"),
            0.16, 130.0),

    BAD_DEPLOYMENT(
            RemediationKind.ROLLBACK_DEPLOY,
            "bad application deployment",
            "5xx spike after a recent deploy",
            "error rate spiked minutes after the latest deployment",
            List.of(
                    "UnsupportedOperationException at CartMapper.toDomain(CartMapper.java:118)",
                    "increased 5xx responses after deploy v2.1.0"),
            0.14, 90.0),

    DISK_PRESSURE(
            RemediationKind.EXPAND_DISK,
            "node disk pressure",
            "volume usage above threshold",
            "the node is evicting pods because the volume is full",
            List.of(
                    "no space left on device",
                    "node /var/lib/containerd: disk usage 94%"),
            0.05, 0.0);

    private final RemediationKind requiredRemediation;
    private final String rootCause;
    private final String symptom;
    private final String incidentSummary;
    private final List<String> logLines;
    private final double errorRateCoefficient;
    private final double latencyCoefficient;

    FaultKind(RemediationKind requiredRemediation, String rootCause, String symptom, String incidentSummary,
            List<String> logLines, double errorRateCoefficient, double latencyCoefficient) {
        this.requiredRemediation = requiredRemediation;
        this.rootCause = rootCause;
        this.symptom = symptom;
        this.incidentSummary = incidentSummary;
        this.logLines = logLines;
        this.errorRateCoefficient = errorRateCoefficient;
        this.latencyCoefficient = latencyCoefficient;
    }

    public RemediationKind requiredRemediation() {
        return requiredRemediation;
    }

    public String rootCause() {
        return rootCause;
    }

    public String symptom() {
        return symptom;
    }

    public String incidentSummary() {
        return incidentSummary;
    }

    public List<String> logLines() {
        return logLines;
    }

    /** Per-unit severity, error-rate increment applied to a service (and, decayed, to its dependents). */
    public double errorRateCoefficient() {
        return errorRateCoefficient;
    }

    /** Per-unit severity, p95 latency increment in milliseconds. */
    public double latencyCoefficient() {
        return latencyCoefficient;
    }
}
