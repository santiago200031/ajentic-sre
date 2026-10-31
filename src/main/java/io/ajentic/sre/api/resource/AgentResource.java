package io.ajentic.sre.api.resource;

import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.session.ChatTurnResult;
import io.ajentic.sre.agent.session.SessionService;
import io.ajentic.sre.agent.session.TurnEvent;
import io.ajentic.sre.agent.session.TurnListener;
import io.ajentic.sre.agent.session.UnknownSessionException;
import io.ajentic.sre.api.payload.AskRequest;
import io.ajentic.sre.api.payload.ApprovalRequest;
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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestPath;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Autonomous ReAct investigation agent. Sessions are bounded by a Caffeine cache so a hosted deployment cannot grow
 * without limit; {@link #ask(AskRequest)} runs a one-shot investigation without leaving a session behind.
 */
@Path("/api/v1/agent")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Tag(name = "Agent", description = "Autonomous ReAct investigation agent and interactive sessions")
@RunOnVirtualThread
public class AgentResource {

    private static final int DEFAULT_MAX_ITERATIONS = 8;

    private final SessionService sessions;
    private final ObjectMapper mapper;
    private final Sse sse;

    @Inject
    public AgentResource(SessionService sessions, ObjectMapper mapper, Sse sse) {
        this.sessions = sessions;
        this.mapper = mapper;
        this.sse = sse;
    }

    @POST
    @Path("/sessions")
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
    @Path("/sessions/{id}")
    @Operation(summary = "Get a session", description = "Returns the session and its current observation.")
    @APIResponse(responseCode = "200", description = "The session")
    @APIResponse(responseCode = "404", description = "Unknown session")
    public SessionView get(@RestPath String id) {
        return SessionView.from(require(id));
    }

    @POST
    @Path("/sessions/{id}/chat")
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
    @Path("/sessions/{id}/chat/stream")
    @Produces("text/event-stream")
    @Operation(summary = "Stream a chat turn as server-sent events",
            description = "Emits thought, tool_call, tool_result, usage and done events as the ReAct turn "
                    + "progresses; one heartbeat comment is sent every 10 seconds while the turn is in flight.")
    @APIResponse(responseCode = "200", description = "The server-sent event stream")
    @APIResponse(responseCode = "404", description = "Unknown session")
    public Response chatStream(@RestPath String id, @Valid ChatRequest request, SseEventSink sink) {
        AgentSession session = require(id);
        AtomicBoolean cancelled = new AtomicBoolean();
        TurnListener listener = event -> send(sink, event, cancelled);
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(
                () -> sink.send(sse.newEventBuilder().comment("heartbeat").build()),
                10, 10, TimeUnit.SECONDS);
        try {
            session.chat(request.message(), listener, cancelled::get);
        } catch (RuntimeException e) {
            send(sink, TurnEvent.error(String.valueOf(e.getMessage())), cancelled);
        } finally {
            heartbeat.shutdownNow();
            try {
                sink.close();
            } catch (IOException ignored) {
            }
        }
        return Response.ok().header("Cache-Control", "no-cache").build();
    }

    private void send(SseEventSink sink, TurnEvent event, AtomicBoolean cancelled) {
        sink.send(sse.newEventBuilder()
                .name(event.type())
                .data(mapper.writeValueAsString(event.payload()))
                .build())
                .toCompletableFuture()
                .exceptionally(ex -> {
                    cancelled.set(true);
                    return null;
                });
    }

    @POST
    @Path("/sessions/{id}/approve")
    @Operation(summary = "Approve or deny a pending mutating tool call",
            description = "Executes the deferred call exactly once on approval; denies or expired approvals append a denied result. Repeats are idempotent.")
    @APIResponse(responseCode = "200", description = "The decision outcome")
    @APIResponse(responseCode = "404", description = "Unknown session or approval id")
    @APIResponse(responseCode = "400", description = "Unknown decision value")
    public Response approve(@RestPath String id, @Valid ApprovalRequest request) {
        AgentSession session = require(id);
        String decision = request.decision().trim().toLowerCase(java.util.Locale.ROOT);
        if (!decision.equals("approve") && !decision.equals("deny")) {
            return error(400, "decision must be 'approve' or 'deny'.");
        }
        var outcome = session.decideApproval(request.approvalId(), decision.equals("approve"), request.note());
        if (outcome == null) {
            return error(404, "Unknown approval id: " + request.approvalId());
        }
        return Response.ok(outcome).build();
    }

    @GET
    @Path("/sessions/{id}/pending")
    @Operation(summary = "List pending approvals", description = "The mutating tool calls still awaiting an operator decision.")
    @APIResponse(responseCode = "200", description = "Pending approvals (empty list when none)")
    @APIResponse(responseCode = "404", description = "Unknown session")
    public java.util.List<io.ajentic.sre.agent.approval.PendingApproval> pending(@RestPath String id) {
        return require(id).pendingApprovals();
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

    @POST
    @Path("/sessions/ask")
    @Operation(hidden = true)
    public ChatResponse askSessions(@Valid AskRequest request) {
        return ask(request);
    }

    @DELETE
    @Path("/sessions/{id}")
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

    private static Response error(int status, String message) {
        return Response.status(status).entity(java.util.Map.of("error", message)).build();
    }
}
