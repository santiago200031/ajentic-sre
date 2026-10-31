package io.ajentic.sre.benchmark;

import io.ajentic.sre.agent.llm.LlmClient;
import io.ajentic.sre.agent.masking.SensitiveDataMasker;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.tool.ToolAuthorization;
import io.ajentic.sre.agent.tool.ToolRegistry;
import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.rl.episode.EpisodeRunner;
import io.ajentic.sre.rl.policy.LlmPolicy;
import io.ajentic.sre.rl.policy.Policy;
import io.ajentic.sre.rl.policy.PolicyFactory;
import io.ajentic.sre.rl.simulation.EnvironmentFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs every (scenario, seed) pair per policy through the shared {@link EpisodeRunner} and rolls the results up
 * into a {@link BenchmarkScorecard}. HEURISTIC and RANDOM are fully deterministic per seed; LLM runs against
 * whatever {@code LlmClient} is configured, so the default {@code scripted} provider keeps the benchmark offline.
 */
@Dependent
public final class BenchmarkRunner {

    private static final String TOKEN_COUNTER = "ajentic.llm.tokens";

    private final EnvironmentFactory environments;
    private final EpisodeRunner runner;
    private final PolicyFactory policies;
    private final LlmClient llm;
    private final ToolRegistry tools;
    private final ToolAuthorization authorization;
    private final SensitiveDataMasker masker;
    private final MeterRegistry meters;

    @Inject
    public BenchmarkRunner(
            EnvironmentFactory environments,
            EpisodeRunner runner,
            PolicyFactory policies,
            LlmClient llm,
            ToolRegistry tools,
            ToolAuthorization authorization,
            SensitiveDataMasker masker,
            MeterRegistry meters) {
        this.environments = environments;
        this.runner = runner;
        this.policies = policies;
        this.llm = llm;
        this.tools = tools;
        this.authorization = authorization;
        this.masker = masker;
        this.meters = meters;
    }

    public BenchmarkScorecard run(List<String> scenarioIds, int seeds, List<String> policyNames, String model) {
        List<BenchmarkScorecard.PolicyScore> scores = new ArrayList<>();
        for (String name : policyNames) {
            scores.add(runPolicy(name, scenarioIds, seeds, model));
        }
        return new BenchmarkScorecard(Instant.now().toString(), scenarioIds, seeds, scores);
    }

    private BenchmarkScorecard.PolicyScore runPolicy(String name, List<String> scenarioIds, int seeds, String model) {
        List<Long> latencies = new ArrayList<>();
        int solved = 0;
        int diagnosed = 0;
        int steps = 0;
        long promptBefore = totalTokens("prompt");
        long completionBefore = totalTokens("completion");

        int episode = 0;
        for (String scenarioId : scenarioIds) {
            for (int seed = 1; seed <= seeds; seed++) {
                Policy policy = policyFor(name, scenarioId, model);
                long start = System.nanoTime();
                EpisodeResult result = runner.run(
                        name + "-" + scenarioId + "-seed" + seed,
                        environments.create(scenarioId, seed),
                        policy);
                latencies.add((System.nanoTime() - start) / 1_000_000);
                episode++;
                solved += result.solved() ? 1 : 0;
                diagnosed += result.diagnosed() ? 1 : 0;
                steps += result.steps();
            }
        }

        latencies.sort(Comparator.naturalOrder());
        double p95 = latencies.isEmpty() ? 0 : latencies.get((int) Math.ceil(0.95 * latencies.size()) - 1);
        return new BenchmarkScorecard.PolicyScore(
                name.toUpperCase(),
                episode,
                solved,
                episode == 0 ? 0 : solved / (double) episode,
                diagnosed,
                episode == 0 ? 0 : diagnosed / (double) episode,
                episode == 0 ? 0 : steps / (double) episode,
                p95,
                totalTokens("prompt") - promptBefore,
                totalTokens("completion") - completionBefore);
    }

    private Policy policyFor(String name, String scenarioId, String model) {
        return switch (name.toUpperCase()) {
            case "HEURISTIC" -> policies.byName("heuristic");
            case "RANDOM" -> policies.byName("random");
            case "LLM" -> new LlmPolicy(
                    llm,
                    tools,
                    authorization,
                    masker,
                    meters,
                    AgentSessionConfig.forScenario(scenarioId).withModel(model == null || model.isBlank() ? null : model));
            default -> throw new IllegalArgumentException("Unknown policy: " + name);
        };
    }

    private long totalTokens(String kind) {
        double total = 0;
        for (Meter meter : meters.getMeters()) {
            Meter.Id id = meter.getId();
            if (meter instanceof Counter counter && TOKEN_COUNTER.equals(id.getName()) && kind.equals(id.getTag("kind"))) {
                total += counter.count();
            }
        }
        return (long) total;
    }
}
