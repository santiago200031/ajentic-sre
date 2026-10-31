package io.ajentic.sre.benchmark;

import java.util.List;

/**
 * Scorecard for one benchmark run: a deterministic per-policy rollup across every (scenario, seed) pair.
 */
public record BenchmarkScorecard(
        String generatedAt,
        List<String> scenarios,
        int seeds,
        List<PolicyScore> policies) {

    public BenchmarkScorecard {
        scenarios = List.copyOf(scenarios);
        policies = List.copyOf(policies);
    }

    public record PolicyScore(
            String policy,
            int episodes,
            int solved,
            double solveRate,
            int diagnosed,
            double diagnosisAccuracy,
            double averageSteps,
            double p95LatencyMs,
            long promptTokens,
            long completionTokens) {
    }
}
