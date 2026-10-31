package io.ajentic.sre.rl.simulation;

import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.ObservedSignal;
import io.ajentic.sre.rl.StepResult;
import io.ajentic.sre.rl.TelemetrySignal;
import io.ajentic.sre.rl.action.QueryTelemetry;
import io.ajentic.sre.rl.reward.IncidentRewardFunction;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The backend selection contract: simulated is the default and stays byte-for-byte deterministic per seed;
 * unknown backends fail fast at construction; live backends are selectable but not implemented yet.
 */
class EnvironmentBackendTest {

    private EnvironmentFactory factory(String backend) {
        return new EnvironmentFactory(
                new ScenarioLibrary(),
                new IncidentRewardFunction(),
                backend,
                "http://localhost:9090",
                "http://localhost:3100",
                Optional.empty(),
                Duration.ofSeconds(5));
    }

    @Test
    void simulatedIsTheDefaultAndStaysDeterministic() {
        EnvironmentFactory factory = factory("simulated");
        assertEquals(EnvironmentFactory.SIMULATED, factory.backend());

        Environment a = factory.create("orders-bad-deployment", 7L);
        Environment b = factory.create("orders-bad-deployment", 7L);

        for (int i = 0; i < 6; i++) {
            StepResult ra = a.step(new QueryTelemetry(TelemetrySignal.METRICS, "orders-api"));
            StepResult rb = b.step(new QueryTelemetry(TelemetrySignal.METRICS, "orders-api"));
            assertEquals(ra.observation(), rb.observation(), "same seed must observe identical state");
        }
        List<ObservedSignal> sa = a.signals(TelemetrySignal.LOGS, "orders-api");
        List<ObservedSignal> sb = b.signals(TelemetrySignal.LOGS, "orders-api");
        assertEquals(sa, sb, "same seed must serve identical signals");
    }

    @Test
    void unknownBackendFailsFastAtConstruction() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> factory("bogus"));
        assertEquals("Unknown environment backend: bogus", exception.getMessage());
    }

    @Test
    void liveBackendsAreSelectableAndTolerateUnreachableEndpoints() {
        Environment local = factory("local-container").create("orders-bad-deployment", 1L);
        Environment cloud = factory("cloud-live").create("orders-bad-deployment", 1L);

        // Unreachable endpoints must not throw; with no readings, nothing is resolved.
        assertFalse(local.isResolved());
        assertFalse(cloud.isResolved());
        assertFalse(local.isTerminal());
    }
}
