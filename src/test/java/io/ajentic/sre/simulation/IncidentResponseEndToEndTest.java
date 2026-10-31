package io.ajentic.sre.simulation;

import io.ajentic.sre.simulation.action.Idle;
import io.ajentic.sre.simulation.drill.SimulationResult;
import io.ajentic.sre.simulation.drill.SimulationRunner;
import io.ajentic.sre.simulation.playbook.HeuristicPlaybook;
import io.ajentic.sre.simulation.scorecard.IncidentScorecard;
import io.ajentic.sre.simulation.scorecard.Transition;
import io.ajentic.sre.simulation.scorecard.TransitionEffect;
import io.ajentic.sre.simulation.Scenario;
import io.ajentic.sre.simulation.ScenarioLibrary;
import io.ajentic.sre.simulation.SimulatedProductionEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentResponseEndToEndTest {

    static Stream<Scenario> scenarios() {
        return new ScenarioLibrary().all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void heuristicPolicyResolvesEveryProductionScenario(Scenario scenario) {
        Environment environment = new SimulatedProductionEnvironment(scenario, 2026L, new IncidentScorecard());

        SimulationResult result = new SimulationRunner().run(scenario.id(), environment, new HeuristicPlaybook());

        assertTrue(result.resolved(), () -> "scenario was not resolved: " + result);
        assertTrue(result.diagnosed(), () -> "root cause was not recorded: " + result);
        assertTrue(result.solved(), () -> "scenario was not solved end to end: " + result);
        assertEquals(TerminationReason.RESOLVED, result.terminationReason(), result::toString);
        assertTrue(result.totalReward() > 0.5, () -> "reward too low: " + result.totalReward());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void initialAlertDoesNotNameTheCulprit(Scenario scenario) {
        Environment environment = new SimulatedProductionEnvironment(scenario, 2026L, new IncidentScorecard());

        Observation first = environment.observe();

        assertFalse(first.feedback().contains(scenario.culpritService()), first.feedback());
        assertEquals(scenario.alertingService(), first.signals().getFirst().service(), first::toString);
        assertNotEquals(scenario.culpritService(), scenario.alertingService());
    }

    @Test
    void penalizesWrongCallsAndCatastrophe() {
        IncidentScorecard reward = new IncidentScorecard();
        Observation observation = observation();
        Transition transition = new Transition(
                new Idle(),
                observation,
                observation,
                EnumSet.of(
                        TransitionEffect.DIAGNOSIS_INCORRECT,
                        TransitionEffect.REMEDIATION_INCORRECT,
                        TransitionEffect.CATASTROPHIC));

        assertEquals(IncidentScorecard.STEP_COST - 0.2 - 0.2 - 1.0, reward.reward(transition), 1e-9);
    }

    private static Observation observation() {
        return new Observation(
                "scenario", "summary", 0, 12, false, false, false, "feedback",
                List.of(), List.of(), List.of(), Map.of(), 0.0);
    }
}
