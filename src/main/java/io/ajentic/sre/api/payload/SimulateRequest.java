package io.ajentic.sre.api.payload;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request for a one-shot incident simulation against a playbook. Runs a scenario against a playbook and returns the
 * simulation result, so a simulation can be launched directly from Swagger UI without starting and running an episode.
 */
@Schema(description = "Request to run a one-shot incident simulation against a playbook.")
public record SimulateRequest(
        @NotBlank(message = "scenarioId must not be blank.")
        @Schema(description = "Scenario ID to simulate.", example = "checkout-latency-db-pool")
        String scenarioId,

        @JsonAlias({"policy", "playbook"})
        @JsonProperty("playbook")
        @Schema(description = "Playbook name: heuristic, idle, random, or llm.", example = "heuristic")
        String playbook,

        @Schema(description = "Deterministic seed.", example = "42")
        Long seed) {

    public String policy() {
        return playbook;
    }
}
