package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to start an episode for a scenario.
 */
@Schema(description = "Request to start an episode for a scenario.")
public record CreateEpisodeRequest(
        @NotBlank(message = "scenarioId must not be blank.")
        @Schema(description = "Scenario to simulate.", example = "checkout-latency-db-pool")
        String scenarioId,

        @Schema(description = "Deterministic random seed.", example = "42")
        Long seed) {
}
