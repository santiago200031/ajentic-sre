package io.ajentic.sre.api.payload;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to run an episode to completion against a named policy or playbook.
 */
@Schema(description = "Request to run an episode to completion against a named policy.")
public record RunEpisodeRequest(
        @JsonAlias({"policy", "playbook"})
        @JsonProperty("policy")
        @Schema(description = "Playbook or policy name: heuristic or idle.", example = "heuristic")
        String policy) {

    public String playbook() {
        return policy;
    }
}
