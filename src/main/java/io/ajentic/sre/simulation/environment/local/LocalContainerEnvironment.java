package io.ajentic.sre.simulation.environment.local;

import io.ajentic.sre.simulation.environment.LiveTelemetryConfig;
import io.ajentic.sre.simulation.environment.LiveTelemetryEnvironment;
import io.ajentic.sre.simulation.scorecard.Scorecard;
import io.ajentic.sre.simulation.Scenario;

/**
 * Live backend for locally managed infrastructure: telemetry from a local Prometheus/Loki pair,
 * remediations dispatched to the configured local endpoint (container restart/scale, LocalStack
 * ECS/Lambda/SSM). Mutations are real here, so {@code ajentic.tools.allow-mutations} still applies.
 */
public final class LocalContainerEnvironment extends LiveTelemetryEnvironment {

    public LocalContainerEnvironment(Scenario scenario, LiveTelemetryConfig config, Scorecard rewardFunction) {
        super(scenario, config, rewardFunction, false);
    }
}
