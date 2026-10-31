package io.ajentic.sre.benchmark;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2-scenario x 2-seed runs must produce a deterministic scorecard for HEURISTIC and RANDOM:
 * same inputs, same rollup (latency excluded).
 */
@QuarkusTest
class BenchmarkRunnerEndToEndTest {

    @Inject
    BenchmarkRunner benchmark;

    @Test
    void scorecardIsDeterministicForHeuristicAndRandom() {
        List<String> scenarios = List.of("orders-bad-deployment", "checkout-latency-db-pool");
        BenchmarkScorecard a = benchmark.run(scenarios, 2, List.of("HEURISTIC", "RANDOM"), null);
        BenchmarkScorecard b = benchmark.run(scenarios, 2, List.of("HEURISTIC", "RANDOM"), null);

        assertEquals(summary(a), summary(b), "same inputs must produce the same scorecard");

        BenchmarkScorecard.PolicyScore heuristic = a.policies().getFirst();
        assertEquals(4, heuristic.episodes());
        assertEquals(1.0, heuristic.solveRate(), "heuristic must solve every scenario and seed");
        assertEquals(4, heuristic.diagnosed());

        BenchmarkScorecard.PolicyScore random = a.policies().get(1);
        assertEquals(4, random.episodes());
        assertEquals(0.0, random.solveRate(), "random never diagnoses or remediates");
        assertEquals(0, random.diagnosed());
    }

    @Test
    void unknownPolicyIsRejected() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> benchmark.run(List.of("orders-bad-deployment"), 1, List.of("NOPE"), null));
        assertEquals("Unknown policy: NOPE", exception.getMessage());
    }

    private List<String> summary(BenchmarkScorecard card) {
        return card.policies().stream()
                .map(score -> score.policy() + ":" + score.episodes() + ":" + score.solved()
                        + ":" + score.diagnosed() + ":" + score.averageSteps()
                        + ":" + score.promptTokens() + ":" + score.completionTokens())
                .toList();
    }
}
