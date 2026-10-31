package io.ajentic.sre.rl.environment.cloud;

import io.ajentic.sre.rl.environment.LiveTelemetryConfig;
import io.ajentic.sre.rl.environment.LiveTelemetryEnvironment;
import io.ajentic.sre.rl.reward.RewardFunction;
import io.ajentic.sre.rl.simulation.Scenario;

/**
 * Read-only cloud backend: telemetry maps onto Prometheus/Grafana Tempo/Loki or native cloud REST APIs
 * without heavy cloud SDKs. Remediations are never executed - {@code apply_remediation} returns a
 * shadow-mode result regardless of {@code ajentic.tools.allow-mutations}.
 */
public final class CloudLiveEnvironment extends LiveTelemetryEnvironment {

    public CloudLiveEnvironment(Scenario scenario, LiveTelemetryConfig config, RewardFunction rewardFunction) {
        super(scenario, config, rewardFunction, true);
    }
}
