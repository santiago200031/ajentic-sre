package io.ajentic.sre.api.payload;

import io.ajentic.sre.rl.Observation;
import io.ajentic.sre.rl.StepResult;
import io.ajentic.sre.rl.TerminationReason;

public record StepResultView(Observation observation, double reward, TerminationReason reason) {

    public static StepResultView from(StepResult result) {
        return new StepResultView(result.observation(), result.reward(), result.reason());
    }
}
