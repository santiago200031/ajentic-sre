package io.ajentic.sre.api.payload;

import io.ajentic.sre.rl.Observation;
import io.ajentic.sre.rl.StepResult;
import io.ajentic.sre.rl.TerminationReason;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Result of a single manual environment step: the observation, the reward earned, and the termination reason.
 */
@Schema(description = "Result of a single manual environment step.")
public record StepResultView(
        @Schema(description = "The observation after the step.")
        Observation observation,

        @Schema(description = "Reward earned by the step.", example = "0.5")
        double reward,

        @Schema(description = "The termination reason, if the step terminated the episode.")
        TerminationReason reason) {

    public static StepResultView from(StepResult result) {
        return new StepResultView(result.observation(), result.reward(), result.reason());
    }
}
