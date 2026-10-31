package io.ajentic.sre.api.resource;

import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.rl.episode.StepRecord;
import io.ajentic.sre.schedule.drill.IncidentDrillRunner;
import io.ajentic.sre.rl.simulation.ScenarioRepository;
import io.ajentic.sre.rl.simulation.UnknownScenarioException;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * Streams an on-demand incident drill as server-sent events: one {@code step} event per episode step and a final
 * {@code done} event with the outcome.
 */
@Path("/api/v1/drills")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Tag(name = "Drills", description = "On-demand incident drills, streamed step by step")
@RunOnVirtualThread
public class DrillResource {

    private final IncidentDrillRunner drills;
    private final ScenarioRepository scenarios;
    private final ObjectMapper mapper;
    private final Sse sse;

    @Inject
    public DrillResource(IncidentDrillRunner drills, ScenarioRepository scenarios, ObjectMapper mapper, Sse sse) {
        this.drills = drills;
        this.scenarios = scenarios;
        this.mapper = mapper;
        this.sse = sse;
    }

    @POST
    @Path("/stream")
    @Produces("text/event-stream")
    @Operation(summary = "Stream a drill as server-sent events",
            description = "Runs a heuristic drill for the scenario and emits one step event per episode step, "
                    + "ending with a done event carrying the outcome.")
    @APIResponse(responseCode = "200", description = "The server-sent event stream")
    @APIResponse(responseCode = "404", description = "Unknown scenario")
    public Response stream(@Valid DrillStreamRequest request, SseEventSink sink) {
        scenarios.byId(request.scenarioId()).orElseThrow(() -> new UnknownScenarioException(request.scenarioId()));
        send(sink, "start", Map.of("scenarioId", request.scenarioId()));
        try {
            EpisodeResult result = drills.drill(request.scenarioId(), (step, observation) ->
                    send(sink, "step", Map.of(
                            "index", step.index(),
                            "action", step.action(),
                            "reward", step.reward(),
                            "feedback", step.feedback(),
                            "terminal", step.terminal())));
            send(sink, "done", Map.of(
                    "scenarioId", result.scenarioId(),
                    "solved", result.solved(),
                    "resolved", result.resolved(),
                    "diagnosed", result.diagnosed(),
                    "reward", result.totalReward(),
                    "steps", result.steps(),
                    "terminationReason", result.terminationReason().name()));
        } finally {
            try {
                sink.close();
            } catch (IOException ignored) {
            }
        }
        return Response.ok().header("Cache-Control", "no-cache").build();
    }

    private void send(SseEventSink sink, String type, Map<String, Object> payload) {
        sink.send(sse.newEventBuilder()
                .name(type)
                .data(mapper.writeValueAsString(payload))
                .build())
                .toCompletableFuture()
                .exceptionally(ex -> null);
    }

    public record DrillStreamRequest(String scenarioId) {
        public DrillStreamRequest {
            if (scenarioId == null || scenarioId.isBlank()) {
                throw new IllegalArgumentException("scenarioId is required");
            }
        }
    }
}
