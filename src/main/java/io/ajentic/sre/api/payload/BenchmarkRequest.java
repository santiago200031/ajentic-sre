package io.ajentic.sre.api.payload;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.util.List;

@Schema(description = "Request to run benchmarks across scenarios, seeds, and policies.")
public record BenchmarkRequest(
        @Schema(description = "Scenario IDs or empty/['all'] for all scenarios.", example = "[\"checkout-latency-db-pool\"]")
        List<String> scenarios,

        @Schema(description = "Number of seeds to evaluate per scenario (default: 2).", example = "2")
        Integer seeds,

        @Schema(description = "Policies to evaluate (HEURISTIC, RANDOM, LLM) or empty/['all'] for all.", example = "[\"HEURISTIC\", \"RANDOM\"]")
        List<String> policies,

        @Schema(description = "Optional model ID for LLM policy.", example = "gpt-4o")
        String model) {
}
