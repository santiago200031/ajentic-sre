package io.ajentic.sre.rl.simulation;

import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.reward.RewardFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class EnvironmentFactory {

    private final ScenarioRepository scenarios;
    private final RewardFunction rewardFunction;

    @Inject
    public EnvironmentFactory(ScenarioRepository scenarios, RewardFunction rewardFunction) {
        this.scenarios = scenarios;
        this.rewardFunction = rewardFunction;
    }

    public Environment create(String scenarioId, long seed) {
        Scenario scenario = scenarios.byId(scenarioId).orElseThrow(() -> new UnknownScenarioException(scenarioId));
        return new SimulatedProductionEnvironment(scenario, seed, rewardFunction);
    }
}
