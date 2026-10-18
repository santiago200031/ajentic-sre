package io.ajentic.sre.cli;

import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.rl.episode.EpisodeRunner;
import io.ajentic.sre.rl.policy.PolicyFactory;
import io.ajentic.sre.rl.simulation.EnvironmentFactory;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Unremovable
@Command(name = "simulate", mixinStandardHelpOptions = true,
        description = "Run an incident scenario with a baseline policy.")
public class SimulateCommand implements Runnable {

    @Inject
    EnvironmentFactory environments;

    @Inject
    PolicyFactory policies;

    @Inject
    EpisodeRunner runner;

    @Option(names = "--scenario", required = true, description = "Scenario id to simulate.")
    String scenarioId;

    @Option(names = "--policy", defaultValue = "heuristic", description = "Policy: heuristic or idle.")
    String policy;

    @Option(names = "--seed", defaultValue = "42", description = "Deterministic seed.")
    long seed;

    @Override
    public void run() {
        Environment environment = environments.create(scenarioId, seed);
        EpisodeResult result = runner.run("cli", environment, policies.byName(policy));
        System.out.printf(
                "scenario=%s solved=%s reward=%.2f steps=%d reason=%s%n",
                result.scenarioId(),
                result.solved(),
                result.totalReward(),
                result.steps(),
                result.terminationReason());
    }
}
