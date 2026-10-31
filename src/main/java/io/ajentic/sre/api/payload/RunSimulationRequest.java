package io.ajentic.sre.api.payload;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to run a simulation to completion against a named playbook.
 */
@Schema(description = "Request to run a simulation to completion against a named playbook.")
public record RunSimulationRequest(
        @JsonAlias({"policy", "playbook"})
        @JsonProperty("playbook")
        @Schema(description = "Playbook name: heuristic, idle, random, or llm.", example = "heuristic")
        String playbook) {

    public String policy() {
        return playbook;
    }
}
