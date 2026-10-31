package io.ajentic.sre.cli;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusMainTest
class CliEndToEndTest {

    @Test
    @Launch({"scenarios"})
    void listsScenarios(LaunchResult result) {
        assertEquals(0, result.exitCode());
        assertTrue(result.getOutput().contains("checkout-latency-db-pool"), result.getOutput());
    }

    @Test
    @Launch({"simulate", "--scenario", "orders-bad-deployment"})
    void simulatesAScenario(LaunchResult result) {
        assertEquals(0, result.exitCode());
        assertTrue(result.getOutput().contains("solved=true"), result.getOutput());
    }

    @Test
    @Launch({"schedules"})
    void listsSchedules(LaunchResult result) {
        assertEquals(0, result.exitCode());
    }

    @Test
    @Launch({"benchmark", "--scenarios", "orders-bad-deployment,checkout-latency-db-pool",
            "--seeds", "2", "--policy", "heuristic,random", "--json"})
    void benchmarkEmitsAScorecard(LaunchResult result) {
        assertEquals(0, result.exitCode());
        String output = result.getOutput();
        assertTrue(output.contains("\"policy\" : \"HEURISTIC\""), output);
        assertTrue(output.contains("\"policy\" : \"RANDOM\""), output);
        assertTrue(output.contains("scorecard written to .ajentic/benchmarks/"), output);
    }

    @Test
    void onlyServeStartsTheHttpListener() {
        assertTrue(CliMain.headless("scenarios"));
        assertTrue(CliMain.headless("mcp"));
        assertFalse(CliMain.headless("serve"));
        assertFalse(CliMain.headless("swagger"));
        assertFalse(CliMain.headless());
    }
}
