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

    public EpisodeResult run(String episodeId, Environment environment, Policy policy) {
        Observation observation = environment.observe();
        List<StepRecord> trajectory = new ArrayList<>();
        TerminationReason reason = TerminationReason.NONE;
        int index = 0;

        while (!observation.terminal()) {
            AgentAction action = policy.next(environment, observation);
            StepResult result = environment.step(action);
            trajectory.add(new StepRecord(index++, action.describe(), result.reward(),
                    result.observation().feedback(), result.done()));
            observation = result.observation();
            reason = result.reason();
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
