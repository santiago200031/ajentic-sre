package io.ajentic.sre.cli;

import tools.jackson.databind.ObjectMapper;
import io.ajentic.sre.benchmark.BenchmarkRunner;
import io.ajentic.sre.benchmark.BenchmarkScorecard;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ScenarioLibrary;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

@Unremovable
@Command(name = "benchmark",
        description = "Benchmark policies across scenarios and seeds; writes a JSON scorecard.")
public class BenchmarkCommand implements Callable<Integer> {

    @Option(names = "--scenarios", defaultValue = "all", description = "Scenario ids, comma separated, or 'all'.")
    String scenarios;

    @Option(names = "--seeds", defaultValue = "2", description = "Number of seeds, 1..N.")
    int seeds;

    @Option(names = "--policy", defaultValue = "all", description = "HEURISTIC, RANDOM, LLM, comma separated, or 'all'.")
    String policy;

    @Option(names = "--model", description = "Model id for the LLM policy (live endpoint).")
    String model;

    @Option(names = "--json", description = "Print the scorecard as JSON.")
    boolean json;

    @Inject
    ScenarioLibrary scenarioLibrary;

    @Inject
    BenchmarkRunner benchmark;

    @Override
    public Integer call() throws Exception {
        List<String> ids = "all".equals(scenarios)
                ? scenarioLibrary.all().stream().map(Scenario::id).toList()
                : List.of(scenarios.split(","));
        List<String> policies = "all".equals(policy)
                ? List.of("HEURISTIC", "RANDOM", "LLM")
                : List.of(policy.split(","));

        BenchmarkScorecard scorecard = benchmark.run(ids, seeds, policies, model);
        ObjectMapper mapper = new ObjectMapper();
        String rendered = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(scorecard);

        Path dir = Path.of(".ajentic", "benchmarks");
        Files.createDirectories(dir);
        Path file = dir.resolve(Instant.now().toString().replace(':', '-') + ".json");
        Files.writeString(file, rendered);

        if (json) {
            System.out.println(rendered);
        } else {
            printTable(scorecard);
        }
        System.out.println("scorecard written to " + file);
        return 0;
    }

    private void printTable(BenchmarkScorecard scorecard) {
        System.out.printf("%-10s %8s %7s %9s %10s %9s %8s %8s %10s%n",
                "policy", "episodes", "solved", "solveRate", "diagnosed", "diagAcc", "avgSteps", "p95Ms", "tokens");
        for (BenchmarkScorecard.PolicyScore score : scorecard.policies()) {
            System.out.printf("%-10s %8d %7d %9.2f %10d %9.2f %8.1f %8.0f %10d%n",
                    score.policy(),
                    score.episodes(),
                    score.solved(),
                    score.solveRate(),
                    score.diagnosed(),
                    score.diagnosisAccuracy(),
                    score.averageSteps(),
                    score.p95LatencyMs(),
                    score.promptTokens() + score.completionTokens());
        }
    }
}
