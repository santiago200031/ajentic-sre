package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request for a one-shot incident investigation. Runs the agent against a scenario and returns its answer, so an
 * investigation can be launched directly from Swagger UI without creating and driving a session.
 */
@Schema(description = "Request to run a one-shot incident investigation.")
public record AskRequest(
        @NotBlank(message = "scenarioId must not be blank.")
        @Schema(description = "Scenario to investigate.", example = "checkout-latency-db-pool")
        String scenarioId,

        @NotBlank(message = "question must not be blank.")
        @Schema(description = "Incident question or prompt to investigate.", example = "Why is checkout-api latency spiking?")
        String question,

        @Schema(description = "Deterministic random seed.", example = "42")
        Long seed,

        @Schema(description = "Optional model override.", example = "Cyber-Tiel-Coder-35B-A3B-UD-Q4_K_XL")
        String model) {
}
