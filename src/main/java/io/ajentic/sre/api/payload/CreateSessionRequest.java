package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to create an investigation session bound to a scenario.
 */
@Schema(description = "Request to create an investigation session.")
public record CreateSessionRequest(
        @NotBlank(message = "scenarioId must not be blank.")
        @Schema(description = "Scenario to investigate.", example = "checkout-latency-db-pool")
        String scenarioId,

        @Schema(description = "Deterministic random seed.", example = "42")
        Long seed,

        @Schema(description = "Optional model override.", example = "Cyber-Tiel-Coder-35B-A3B-UD-Q4_K_XL")
        String model) {
}
