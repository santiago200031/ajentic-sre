package io.ajentic.sre.rl.simulation;

import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.environment.LiveTelemetryConfig;
import io.ajentic.sre.rl.environment.cloud.CloudLiveEnvironment;
import io.ajentic.sre.rl.environment.local.LocalContainerEnvironment;
import io.ajentic.sre.rl.reward.RewardFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

/**
 * Selects the environment backend from {@code ajentic.environment.backend}:
 * <ul>
 *   <li>{@code simulated} (default, deterministic, offline) - the only backend used by tests and CI;</li>
 *   <li>{@code local-container} / {@code cloud-live} - reserved for live adapters, not implemented yet.</li>
 * </ul>
 * Unknown backend values fail fast at startup.
 */
@ApplicationScoped
public class EnvironmentFactory {

    public static final String SIMULATED = "simulated";
    public static final String LOCAL_CONTAINER = "local-container";
    public static final String CLOUD_LIVE = "cloud-live";

    private static final Logger LOG = Logger.getLogger(EnvironmentFactory.class);

    private final ScenarioRepository scenarios;
    private final RewardFunction rewardFunction;
    private final String backend;
    private final LiveTelemetryConfig liveConfig;

    @Inject
    public EnvironmentFactory(
            ScenarioRepository scenarios,
            RewardFunction rewardFunction,
            @ConfigProperty(name = "ajentic.environment.backend", defaultValue = SIMULATED) String backend,
            @ConfigProperty(name = "ajentic.live.prometheus-url", defaultValue = "http://localhost:9090")
            String prometheusUrl,
            @ConfigProperty(name = "ajentic.live.loki-url", defaultValue = "http://localhost:3100") String lokiUrl,
            @ConfigProperty(name = "ajentic.live.remediation-url") Optional<String> remediationUrl,
            @ConfigProperty(name = "ajentic.live.timeout", defaultValue = "5s") Duration timeout) {
        this.scenarios = scenarios;
        this.rewardFunction = rewardFunction;
        this.liveConfig = new LiveTelemetryConfig(prometheusUrl, lokiUrl, remediationUrl.orElse(""), timeout);
        this.backend = switch (backend == null ? "" : backend.trim().toLowerCase()) {
            case SIMULATED, LOCAL_CONTAINER, CLOUD_LIVE -> backend.trim().toLowerCase();
            default -> throw new IllegalStateException("Unknown environment backend: " + backend);
        };
        LOG.infof("Environment backend: %s", this.backend);
    }

    public String backend() {
        return backend;
    }

    public Environment create(String scenarioId, long seed) {
        Scenario scenario = scenarios.byId(scenarioId).orElseThrow(() -> new UnknownScenarioException(scenarioId));
        return switch (backend) {
            case SIMULATED -> new SimulatedProductionEnvironment(scenario, seed, rewardFunction);
            case LOCAL_CONTAINER -> new LocalContainerEnvironment(scenario, liveConfig, rewardFunction);
            case CLOUD_LIVE -> new CloudLiveEnvironment(scenario, liveConfig, rewardFunction);
            default -> throw new IllegalStateException("Unknown environment backend: " + backend);
        };
    }
}
