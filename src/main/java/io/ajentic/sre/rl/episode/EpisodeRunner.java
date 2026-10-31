package io.ajentic.sre.rl.episode;

import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.Observation;
import io.ajentic.sre.rl.StepResult;
import io.ajentic.sre.rl.TerminationReason;
import io.ajentic.sre.rl.action.AgentAction;
import io.ajentic.sre.rl.policy.Policy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class EpisodeRunner {

    /**
     * Observes every step of an episode, e.g. for streaming drills over SSE.
     */
    @FunctionalInterface
    public interface StepListener {
        void onStep(StepRecord step, Observation observation);
    }

    public EpisodeResult run(String episodeId, Environment environment, Policy policy) {
        return run(episodeId, environment, policy, null);
    }

    public EpisodeResult run(String episodeId, Environment environment, Policy policy, StepListener listener) {
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

        return new EpisodeResult(
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
