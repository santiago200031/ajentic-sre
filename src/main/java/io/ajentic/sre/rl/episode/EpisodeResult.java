package io.ajentic.sre.rl.episode;

import io.ajentic.sre.rl.TerminationReason;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Result of a completed episode: whether it was solved/resolved/diagnosed, the reward earned, the step count, the
 * termination reason, and the full trajectory of step records.
 */
@Schema(description = "Result of a completed episode.")
public record EpisodeResult(
        @Schema(description = "Episode id.", example = "e1a2b3c4-...")
        String episodeId,

        @Schema(description = "Scenario id that was simulated.", example = "checkout-latency-db-pool")
        String scenarioId,

        @Schema(description = "Whether the episode reached a solved state.", example = "true")
        boolean solved,

        @Schema(description = "Whether the incident was resolved.", example = "true")
        boolean resolved,

        @Schema(description = "Whether the root cause was diagnosed.", example = "true")
        boolean diagnosed,

        @Schema(description = "Total reward earned.", example = "1.0")
        double totalReward,

        @Schema(description = "Number of agent steps taken.", example = "5")
        int steps,

        @Schema(description = "Why the episode terminated.")
        TerminationReason terminationReason,

        @Schema(description = "The full trajectory of step records.")
        List<StepRecord> trajectory) {

    public EpisodeResult {
        trajectory = trajectory == null ? List.of() : List.copyOf(trajectory);
    }
}
