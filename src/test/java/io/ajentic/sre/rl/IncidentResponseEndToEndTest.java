package io.ajentic.sre.rl;

import io.ajentic.sre.rl.action.Idle;
import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.rl.episode.EpisodeRunner;
import io.ajentic.sre.rl.policy.HeuristicPolicy;
import io.ajentic.sre.rl.reward.IncidentRewardFunction;
import io.ajentic.sre.rl.reward.Transition;
import io.ajentic.sre.rl.reward.TransitionEffect;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ScenarioLibrary;
import io.ajentic.sre.rl.simulation.SimulatedProductionEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentResponseEndToEndTest {

    static Stream<Scenario> scenarios() {
        return new ScenarioLibrary().all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void heuristicPolicyResolvesEveryProductionScenario(Scenario scenario) {
        Environment environment = new SimulatedProductionEnvironment(scenario, 2026L, new IncidentRewardFunction());

        EpisodeResult result = new EpisodeRunner().run(scenario.id(), environment, new HeuristicPolicy());

        assertTrue(result.resolved(), () -> "scenario was not resolved: " + result);
        assertTrue(result.diagnosed(), () -> "root cause was not recorded: " + result);
        assertTrue(result.solved(), () -> "scenario was not solved end to end: " + result);
        assertEquals(TerminationReason.RESOLVED, result.terminationReason(), result::toString);
        assertTrue(result.totalReward() > 0.5, () -> "reward too low: " + result.totalReward());
    }

    @Test
    void penalizesWrongCallsAndCatastrophe() {
        IncidentRewardFunction reward = new IncidentRewardFunction();
        Observation observation = observation();
        Transition transition = new Transition(
                new Idle(),
                observation,
                observation,
                EnumSet.of(
                        TransitionEffect.DIAGNOSIS_INCORRECT,
                        TransitionEffect.REMEDIATION_INCORRECT,
                        TransitionEffect.CATASTROPHIC));

        assertEquals(IncidentRewardFunction.STEP_COST - 0.2 - 0.2 - 1.0, reward.reward(transition), 1e-9);
    }

    private static Observation observation() {
        return new Observation(
                "scenario", "summary", 0, 12, false, false, false, "feedback",
                List.of(), List.of(), List.of(), Map.of(), 0.0);
    }
}
