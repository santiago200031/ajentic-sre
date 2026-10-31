package io.ajentic.sre.simulation.environment.cloud;

import io.ajentic.sre.simulation.environment.LiveTelemetryConfig;
import io.ajentic.sre.simulation.environment.LiveTelemetryEnvironment;
import io.ajentic.sre.simulation.scorecard.Scorecard;
import io.ajentic.sre.simulation.Scenario;

/**
 * Read-only cloud backend: telemetry maps onto Prometheus/Grafana Tempo/Loki or native cloud REST APIs
 * without heavy cloud SDKs. Remediations are never executed - {@code apply_remediation} returns a
 * shadow-mode result regardless of {@code ajentic.tools.allow-mutations}.
 */
public final class CloudLiveEnvironment extends LiveTelemetryEnvironment {

    public CloudLiveEnvironment(Scenario scenario, LiveTelemetryConfig config, Scorecard rewardFunction) {
        super(scenario, config, rewardFunction, true);
    }
}
