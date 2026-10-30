package io.ajentic.sre.api.resource;

import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.session.ChatTurnResult;
import io.ajentic.sre.agent.session.SessionService;
import io.ajentic.sre.agent.session.UnknownSessionException;
import io.ajentic.sre.api.payload.AskRequest;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Autonomous ReAct investigation agent. Sessions are bounded by a Caffeine cache so a hosted deployment cannot grow
 * without limit; {@link #ask(AskRequest)} runs a one-shot investigation without leaving a session behind.
 */
@Path("/api/v1/agent/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Tag(name = "Agent", description = "Autonomous ReAct investigation agent and interactive sessions")
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
    @Operation(summary = "Create an investigation session", description = "Starts a session bound to a scenario and seed.")
    @APIResponse(responseCode = "201", description = "Created session")
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
    @Operation(summary = "Get a session", description = "Returns the session and its current observation.")
    @APIResponse(responseCode = "200", description = "The session")
    @APIResponse(responseCode = "404", description = "Unknown session")
    public SessionView get(@RestPath String id) {
        return SessionView.from(require(id));
    }

    @POST
    @Path("/{id}/chat")
    @Operation(summary = "Send a chat turn", description = "Advances the agent with a user message and returns the turn result.")
    @APIResponse(responseCode = "200", description = "The chat turn result")
    @APIResponse(responseCode = "404", description = "Unknown session")
    public ChatResponse chat(@RestPath String id, @Valid ChatRequest request) {
        AgentSession session = require(id);
        ChatTurnResult result = session.chat(request.message());
        return new ChatResponse(session.id(), result.answer(), result.observation(), result.toolCalls(),
                result.usage());
    }

    @POST
    @Path("/ask")
    @Operation(summary = "One-shot investigation", description = "Runs the agent against a scenario for a single question and returns the answer and token usage. No session is retained.")
    @APIResponse(responseCode = "200", description = "The agent's answer and token usage")
    public ChatResponse ask(@Valid AskRequest request) {
        long seed = request.seed() == null ? 42L : request.seed();
        AgentSessionConfig config = new AgentSessionConfig(
                request.scenarioId(), seed, request.model(), DEFAULT_MAX_ITERATIONS);
        AgentSession session = sessions.create(config);
        try {
            ChatTurnResult result = session.chat(request.question());
            return new ChatResponse(session.id(), result.answer(), result.observation(), result.toolCalls(),
                    result.usage());
        } finally {
            sessions.delete(session.id());
        }
    }

    @DELETE
    @Path("/{id}")
    @ResponseStatus(204)
    @Operation(summary = "Delete a session", description = "Removes an investigation session.")
    @APIResponse(responseCode = "204", description = "Deleted")
    @APIResponse(responseCode = "404", description = "Unknown session")
    public void delete(@RestPath String id) {
        require(id);
        sessions.delete(id);
    }

    private AgentSession require(String id) {
        return sessions.find(id).orElseThrow(() -> new UnknownSessionException(id));
    }
}
