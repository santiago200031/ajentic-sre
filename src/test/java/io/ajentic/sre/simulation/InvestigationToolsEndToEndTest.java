package io.ajentic.sre.simulation;

import io.ajentic.sre.simulation.action.CorrelateAnomalies;
import io.ajentic.sre.simulation.action.Idle;
import io.ajentic.sre.simulation.action.InspectRecentChanges;
import io.ajentic.sre.simulation.scorecard.IncidentScorecard;
import io.ajentic.sre.simulation.Scenario;
import io.ajentic.sre.simulation.ScenarioLibrary;
import io.ajentic.sre.simulation.SimulatedProductionEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks for the investigation tools: {@code inspect_recent_changes} surfaces the bad deploy inside
 * (but not outside) its window, {@code correlate_anomalies} ranks the culprit first through the topology, and both
 * are step-counted reads that pay the step penalty and respect {@code maxSteps}.
 */
class InvestigationToolsEndToEndTest {

    private static Scenario ordersScenario() {
        return new ScenarioLibrary().byId("orders-bad-deployment")
                .orElseThrow(() -> new AssertionError("built-in scenario missing"));
    }

    private static Environment environment(Scenario scenario) {
        return new SimulatedProductionEnvironment(scenario, 7L, new IncidentScorecard());
    }

    @Test
    void inspectSurfacesTheBadDeployOnlyInsideItsWindow() {
        Environment environment = environment(ordersScenario());

        StepResult inside = environment.step(new InspectRecentChanges("orders-api", 60));
        String insideText = inside.observation().signals().stream().map(ObservedSignal::summary).toList().toString();
        assertTrue(insideText.contains("v2.1.0"), insideText);
        assertTrue(insideText.contains("- v1.0.0"), insideText);
        assertTrue(insideText.contains("+ v2.1.0"), insideText);
        assertTrue(inside.observation().signals().stream().anyMatch(s -> s.attributes().get("kind").equals("DEPLOY")),
                inside.observation().toString());

        StepResult outside = environment.step(new InspectRecentChanges("orders-api", 10));
        assertFalse(outside.observation().signals().stream().anyMatch(s -> s.summary().contains("v2.1.0")),
                outside.observation().toString());
    }

    @Test
    void correlateRanksTheCulpritFirstAcrossTheNeighborhood() {
        Environment environment = environment(ordersScenario());

        StepResult result = environment.step(new CorrelateAnomalies(List.of("edge-gateway"), 2));
        String ranking = result.observation().feedback();
        assertTrue(ranking.contains("orders-api"), ranking);
        assertTrue(ranking.contains("orders-db"), ranking);
        assertTrue(ranking.contains("edge-gateway"), ranking);
        int culprit = ranking.indexOf("1. orders-api");
        assertTrue(culprit >= 0, "culprit must rank first: " + ranking);
        assertTrue(culprit < ranking.indexOf("edge-gateway"), ranking);
    }

    @Test
    void investigationToolsPayTheStepPenaltyAndRespectMaxSteps() {
        Scenario scenario = ordersScenario();
        Environment environment = environment(scenario);

        StepResult inspect = environment.step(new InspectRecentChanges("orders-api", 1440));
        assertEquals(IncidentScorecard.STEP_COST, inspect.reward(), 1e-9);
        assertEquals(1, inspect.observation().step());

        StepResult correlate = environment.step(new CorrelateAnomalies(List.of(), 2));
        assertEquals(IncidentScorecard.STEP_COST, correlate.reward(), 1e-9);
        assertEquals(2, correlate.observation().step());

        StepResult last;
        do {
            last = environment.step(new Idle());
        } while (!last.done());
        assertEquals(TerminationReason.MAX_STEPS, last.reason(), last::toString);
    }
}
