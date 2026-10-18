package io.ajentic.sre.api.payload;

import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.rl.Observation;

public record SessionView(String id, Observation observation) {

    public static SessionView from(AgentSession session) {
        return new SessionView(session.id(), session.environment().observe());
    }
}
