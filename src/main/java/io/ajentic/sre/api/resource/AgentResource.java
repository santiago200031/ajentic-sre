package io.ajentic.sre.api.resource;

import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.session.ChatTurnResult;
import io.ajentic.sre.agent.session.SessionService;
import io.ajentic.sre.agent.session.UnknownSessionException;
import io.ajentic.sre.api.payload.ChatRequest;
import io.ajentic.sre.api.payload.ChatResponse;
import io.ajentic.sre.api.payload.CreateSessionRequest;
import io.ajentic.sre.api.payload.SessionView;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestPath;

@Path("/api/v1/agent/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class AgentResource {

    private static final int DEFAULT_MAX_ITERATIONS = 8;

    private final SessionService sessions;

    @Inject
    public AgentResource(SessionService sessions) {
        this.sessions = sessions;
    }

    @POST
    @ResponseStatus(201)
    public SessionView create(@Valid CreateSessionRequest request) {
        AgentSessionConfig config = new AgentSessionConfig(
                request.scenarioId(),
                request.seed() == null ? 42L : request.seed(),
                request.model(),
                DEFAULT_MAX_ITERATIONS);
        AgentSession session = sessions.create(config);
        return SessionView.from(session);
    }

    @GET
    @Path("/{id}")
    public SessionView get(@RestPath String id) {
        return SessionView.from(require(id));
    }

    @POST
    @Path("/{id}/chat")
    public ChatResponse chat(@RestPath String id, @Valid ChatRequest request) {
        AgentSession session = require(id);
        ChatTurnResult result = session.chat(request.message());
        return new ChatResponse(session.id(), result.answer(), result.observation(), result.toolCalls());
    }

    @DELETE
    @Path("/{id}")
    @ResponseStatus(204)
    public void delete(@RestPath String id) {
        require(id);
        sessions.delete(id);
    }

    private AgentSession require(String id) {
        return sessions.find(id).orElseThrow(() -> new UnknownSessionException(id));
    }
}
