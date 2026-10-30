package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request for a one-shot incident simulation. Runs a scenario against a baseline policy and returns the episode
 * result, so a simulation can be launched directly from Swagger UI without starting and running an episode.
 */
@Schema(description = "Request to run a one-shot incident simulation against a baseline policy.")
public record SimulateRequest(
        @NotBlank(message = "scenarioId must not be blank.")
        @Schema(description = "Scenario ID to simulate.", example = "checkout-latency-db-pool")
        String scenarioId,

        @Schema(description = "Policy name: heuristic or idle.", example = "heuristic")
        String policy,

        @Schema(description = "Deterministic seed.", example = "42")
        Long seed) {
}
