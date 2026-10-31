package io.ajentic.sre.rl.simulation;

import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.reward.RewardFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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

    @Inject
    public EnvironmentFactory(
            ScenarioRepository scenarios,
            RewardFunction rewardFunction,
            @ConfigProperty(name = "ajentic.environment.backend", defaultValue = SIMULATED) String backend) {
        this.scenarios = scenarios;
        this.rewardFunction = rewardFunction;
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
            case LOCAL_CONTAINER -> throw new UnsupportedOperationException(
                    "local-container backend is not implemented yet; use ajentic.environment.backend=simulated");
            case CLOUD_LIVE -> throw new UnsupportedOperationException(
                    "cloud-live backend is not implemented yet; use ajentic.environment.backend=simulated");
            default -> throw new IllegalStateException("Unknown environment backend: " + backend);
        };
    }
}
