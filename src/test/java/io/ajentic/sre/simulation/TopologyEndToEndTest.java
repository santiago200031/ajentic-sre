package io.ajentic.sre.simulation;

import io.ajentic.sre.simulation.action.Idle;
import io.ajentic.sre.simulation.drill.SimulationResult;
import io.ajentic.sre.simulation.drill.SimulationRunner;
import io.ajentic.sre.simulation.playbook.HeuristicPlaybook;
import io.ajentic.sre.simulation.scorecard.IncidentScorecard;
import io.ajentic.sre.simulation.Fault;
import io.ajentic.sre.simulation.FaultKind;
import io.ajentic.sre.simulation.Runbook;
import io.ajentic.sre.simulation.Scenario;
import io.ajentic.sre.simulation.ServiceKind;
import io.ajentic.sre.simulation.ServiceSpec;
import io.ajentic.sre.simulation.ServiceTopology;
import io.ajentic.sre.simulation.SimulatedProductionEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end checks for service-topology cascading: a fault on a leaf degrades every ancestor with depth-decayed
 * severity, invalid graphs are rejected at load, and the symptom-first alert still appears on the edge service.
 */
class TopologyEndToEndTest {

    static Scenario leafCulpritScenario() {
        return new Scenario(
                "leaf-fault-cascade",
                "Leaf database pool exhaustion cascades upstream",
                "A saturated leaf database degrades its API and the gateway.",
                FaultKind.DB_POOL_EXHAUSTION.incidentSummary(),
                List.of(
                        new ServiceSpec("edge-gateway", ServiceKind.GATEWAY, 3, List.of("checkout-api")),
                        new ServiceSpec("checkout-api", ServiceKind.API, 4, List.of("orders-db")),
                        new ServiceSpec("orders-db", ServiceKind.DATABASE, 1)),
                List.of(new Fault(FaultKind.DB_POOL_EXHAUSTION, "orders-db", 1.0)),
                List.of(new Runbook("rb-db-pool", "Database connection pool saturation",
                        "Confirm pool saturation in the metrics, then scale out the database and verify recovery.")),
                12);
    }

    @Test
    void rejectsCyclesAndUnknownDependencies() {
        Scenario cyclic = new Scenario(
                "cycle", "t", "s", "i",
                List.of(
                        new ServiceSpec("a", ServiceKind.API, 1, List.of("b")),
                        new ServiceSpec("b", ServiceKind.API, 1, List.of("a"))),
                List.of(), List.of(), 12);
        assertThrows(IllegalArgumentException.class, cyclic::topology);
        assertThrows(IllegalArgumentException.class,
                () -> new SimulatedProductionEnvironment(cyclic, 1L, new IncidentScorecard()));

        Scenario dangling = new Scenario(
                "dangling", "t", "s", "i",
                List.of(new ServiceSpec("a", ServiceKind.API, 1, List.of("ghost"))),
                List.of(), List.of(), 12);
        assertThrows(IllegalArgumentException.class, dangling::topology);
    }

    @Test
    void topologyAncestorsFollowTheDag() {
        ServiceTopology topology = leafCulpritScenario().topology();
        assertEquals(List.of("checkout-api", "edge-gateway"), topology.ancestorsOf("orders-db"));
        assertEquals(List.of("edge-gateway"), topology.ancestorsOf("checkout-api"));
        assertEquals(List.of(), topology.ancestorsOf("edge-gateway"));
    }

    @Test
    void faultOnALeafDegradesAllAncestorsWithDepthDecay() {
        Environment environment = new SimulatedProductionEnvironment(leafCulpritScenario(), 1L, new IncidentScorecard());

        // Initial fault application (scale 1.0): depth-1 ancestor gets 0.5x severity, depth-2 gets 0.25x.
        // Telemetry attributes are rounded to two decimals, so 0.015 reports as 0.02.
        assertEquals(0.03, errorRate(environment, "checkout-api"), 1e-9);
        assertEquals(0.02, errorRate(environment, "edge-gateway"), 1e-9);

        for (int i = 0; i < 6; i++) {
            environment.step(new Idle());
        }
        Observation after = environment.observe();
        assertEquals(ServiceHealth.DEGRADED, after.serviceHealth().get("checkout-api"), after::toString);
        assertEquals(ServiceHealth.DEGRADED, after.serviceHealth().get("edge-gateway"), after::toString);
        assertTrue(errorRate(environment, "edge-gateway") < errorRate(environment, "checkout-api"),
                "deeper ancestors must degrade less than nearer ones");
    }

    @Test
    void symptomFirstAlertAppearsOnTheEdgeWhileTheCulpritIsALeaf() {
        Environment environment = new SimulatedProductionEnvironment(leafCulpritScenario(), 1L, new IncidentScorecard());
        Observation first = environment.observe();
        assertTrue(first.feedback().contains("edge-gateway"), first.feedback());
        assertFalse(first.feedback().contains("orders-db"), first.feedback());
        assertEquals("edge-gateway", first.signals().getFirst().service(), first::toString);
    }

    @Test
    void heuristicPolicyStillSolvesWhenTheCulpritIsALeaf() {
        Scenario scenario = leafCulpritScenario();
        Environment environment = new SimulatedProductionEnvironment(scenario, 1L, new IncidentScorecard());
        SimulationResult result = new SimulationRunner().run(scenario.id(), environment, new HeuristicPlaybook());
        assertTrue(result.solved(), result::toString);
        assertTrue(result.diagnosed(), result::toString);
        assertEquals(TerminationReason.RESOLVED, result.terminationReason(), result::toString);
    }

    private static double errorRate(Environment environment, String service) {
        Object errorRate = environment.signals(TelemetrySignal.METRICS, service).stream()
                .filter(signal -> signal.attributes().containsKey("errorRate"))
                .findFirst()
                .orElseThrow()
                .attributes()
                .get("errorRate");
        return ((Number) errorRate).doubleValue();
    }
}
