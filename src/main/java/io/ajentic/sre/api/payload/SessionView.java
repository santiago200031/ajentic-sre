package io.ajentic.sre.api.payload;

import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.rl.Observation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * View of an investigation session: its id and the current environment observation.
 */
@Schema(description = "View of an investigation session.")
public record SessionView(
        @Schema(description = "Session id.", example = "b3f1c2d4-...")
        String id,

        @Schema(description = "The current environment observation.")
        Observation observation) {

    public static SessionView from(AgentSession session) {
        return new SessionView(session.id(), session.environment().observe());
    }
}
