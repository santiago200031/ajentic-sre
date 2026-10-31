package io.ajentic.sre.api.payload;

import io.ajentic.sre.simulation.drill.SimulationHandle;
import io.ajentic.sre.simulation.drill.SimulationResult;
import io.ajentic.sre.simulation.Observation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * View of an episode: its id, the current observation, whether it has completed, and its final result if present.
 */
@Schema(description = "View of an episode.")
public record EpisodeView(
        @Schema(description = "Episode id.", example = "e1a2b3c4-...")
        String id,

        @Schema(description = "The current environment observation.")
        Observation observation,

        @Schema(description = "Whether the episode has completed.", example = "false")
        boolean completed,

        @Schema(description = "The final episode result, present once the episode completes.")
        SimulationResult result) {

    public static EpisodeView from(SimulationHandle handle) {
        return new EpisodeView(
                handle.id(),
                handle.environment().observe(),
                handle.result().isPresent(),
                handle.result().orElse(null));
    }
}
