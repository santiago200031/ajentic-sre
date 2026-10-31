package io.ajentic.sre.simulation.drill;

import io.ajentic.sre.simulation.Environment;
import io.ajentic.sre.simulation.Observation;
import io.ajentic.sre.simulation.StepResult;
import io.ajentic.sre.simulation.TerminationReason;
import io.ajentic.sre.simulation.action.AgentAction;
import io.ajentic.sre.simulation.playbook.Playbook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SimulationRunner {

    /**
     * Observes every step of an episode, e.g. for streaming drills over SSE.
     */
    @FunctionalInterface
    public interface StepListener {
        void onStep(StepRecord step, Observation observation);
    }

    public SimulationResult run(String episodeId, Environment environment, Playbook policy) {
        return run(episodeId, environment, policy, null);
    }

    public SimulationResult run(String episodeId, Environment environment, Playbook policy, StepListener listener) {
        Observation observation = environment.observe();
        List<StepRecord> trajectory = new ArrayList<>();
        TerminationReason reason = TerminationReason.NONE;
        int index = 0;

        while (!observation.terminal()) {
            AgentAction action = policy.next(environment, observation);
            StepResult result = environment.step(action);
            StepRecord step = new StepRecord(index++, action.describe(), result.reward(),
                    result.observation().feedback(), result.done());
            trajectory.add(step);
            observation = result.observation();
            reason = result.reason();
            if (listener != null) {
                listener.onStep(step, observation);
            }
        }

        return new SimulationResult(
                episodeId,
                environment.spec().scenarioId(),
                observation.diagnosed() && observation.resolved(),
                observation.resolved(),
                observation.diagnosed(),
                observation.cumulativeReward(),
                trajectory.size(),
                reason,
                trajectory);
    }
}
