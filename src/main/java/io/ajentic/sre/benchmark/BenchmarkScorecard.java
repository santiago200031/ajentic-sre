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

    public String toFormattedReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========================================================================================\n");
        sb.append("                         AJENTIC-SRE BENCHMARK SCORECARD REPORT\n");
        sb.append("========================================================================================\n");
        sb.append(String.format("%-12s | %-8s | %-10s | %-10s | %-9s | %-12s | %-10s | %-10s\n",
                "Playbook", "Episodes", "Solve Rate", "Diag Acc", "Avg Steps", "P95 Latency", "Prompt Tok", "Compl Tok"));
        sb.append("----------------------------------------------------------------------------------------\n");
        for (PolicyScore s : policies) {
            sb.append(String.format("%-12s | %-8d | %-9.1f%% | %-9.1f%% | %-9.1f | %-10.1fms | %-10d | %-10d\n",
                    s.policy(), s.episodes(), s.solveRate() * 100.0, s.diagnosisAccuracy() * 100.0,
                    s.averageSteps(), s.p95LatencyMs(), s.promptTokens(), s.completionTokens()));
        }
        sb.append("========================================================================================\n");
        return sb.toString();
    }
}

