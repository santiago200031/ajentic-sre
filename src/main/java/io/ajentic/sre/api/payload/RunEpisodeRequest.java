package io.ajentic.sre.api.payload;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to run an episode to completion against a named policy.
 */
@Schema(description = "Request to run an episode to completion against a named policy.")
public record RunEpisodeRequest(
        @Schema(description = "Policy name: heuristic or idle.", example = "heuristic")
        String policy) {
}
