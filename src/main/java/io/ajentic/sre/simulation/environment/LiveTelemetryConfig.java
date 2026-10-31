package io.ajentic.sre.simulation.environment;

import java.time.Duration;

/**
 * Endpoints and bounds for the live telemetry backends.
 *
 * @param prometheusUrl   base URL of a Prometheus-compatible store (e.g. http://localhost:9090)
 * @param lokiUrl         base URL of a Loki-compatible store (e.g. http://localhost:3100)
 * @param remediationUrl  endpoint that executes remediations (local-container backend only)
 * @param timeout         connect and request timeout; requests never block longer than this
 */
public record LiveTelemetryConfig(String prometheusUrl, String lokiUrl, String remediationUrl, Duration timeout) {
}
