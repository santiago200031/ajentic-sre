package io.ajentic.sre.api.resource;

import io.ajentic.sre.api.mapper.ActionMapper;
import io.ajentic.sre.api.payload.ActionRequest;
import io.ajentic.sre.api.payload.CreateEpisodeRequest;
import io.ajentic.sre.api.payload.EpisodeView;
import io.ajentic.sre.api.payload.RunEpisodeRequest;
import io.ajentic.sre.api.payload.SimulateRequest;
import io.ajentic.sre.api.payload.StepResultView;
import io.ajentic.sre.rl.StepResult;
import io.ajentic.sre.rl.episode.EpisodeHandle;
import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.rl.episode.EpisodeService;
import io.ajentic.sre.rl.episode.UnknownEpisodeException;
import io.ajentic.sre.rl.policy.PolicyFactory;
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
 * Deterministic incident simulation and RL policy evaluation. Episodes are bounded by a Caffeine cache;
 * {@link #simulate(SimulateRequest)} runs a one-shot simulation without leaving an episode behind.
 */
@Path("/api/v1/episodes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Tag(name = "Episodes", description = "Deterministic incident simulation and RL policy evaluation")
@RunOnVirtualThread
public class EpisodeResource {

    private final EpisodeService episodes;
    private final PolicyFactory policies;

    @Inject
    public EpisodeResource(EpisodeService episodes, PolicyFactory policies) {
        this.episodes = episodes;
        this.policies = policies;
    }

    @POST
    @ResponseStatus(201)
    @Operation(summary = "Start an episode", description = "Initialises an episode for a scenario and seed.")
    @APIResponse(responseCode = "201", description = "Started episode")
    public EpisodeView start(@Valid CreateEpisodeRequest request) {
        long seed = request.seed() == null ? 42L : request.seed();
        EpisodeHandle handle = episodes.start(request.scenarioId(), seed);
        return EpisodeView.from(handle);
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get an episode", description = "Returns the episode and its current state.")
    @APIResponse(responseCode = "200", description = "The episode")
    @APIResponse(responseCode = "404", description = "Unknown episode")
    public EpisodeView get(@RestPath String id) {
        return EpisodeView.from(require(id));
    }

    @POST
    @Path("/{id}/actions")
    @Operation(summary = "Step the environment", description = "Applies a single manual action to the episode environment.")
    @APIResponse(responseCode = "200", description = "The step result")
    @APIResponse(responseCode = "404", description = "Unknown episode")
    public StepResultView step(@RestPath String id, @Valid ActionRequest request) {
        StepResult result = require(id).environment().step(ActionMapper.toAction(request));
        return StepResultView.from(result);
    }

    @POST
    @Path("/{id}/run")
    @Operation(summary = "Run an episode", description = "Runs the episode to completion against a named policy.")
    @APIResponse(responseCode = "200", description = "The episode result")
    @APIResponse(responseCode = "404", description = "Unknown episode")
    public EpisodeResult run(@RestPath String id, RunEpisodeRequest request) {
        require(id);
        return episodes.run(id, policies.byName(request == null ? null : request.policy()));
    }

    @POST
    @Path("/simulate")
    @Operation(summary = "One-shot simulation", description = "Starts an episode for a scenario, runs it to completion against a policy, and returns the result. No episode is retained.")
    @APIResponse(responseCode = "200", description = "The episode result")
    public EpisodeResult simulate(@Valid SimulateRequest request) {
        long seed = request.seed() == null ? 42L : request.seed();
        EpisodeHandle handle = episodes.start(request.scenarioId(), seed);
        try {
            return episodes.run(handle.id(), policies.byName(request.policy()));
        } finally {
            episodes.delete(handle.id());
        }
    }

    @DELETE
    @Path("/{id}")
    @ResponseStatus(204)
    @Operation(summary = "Delete an episode", description = "Removes an episode.")
    @APIResponse(responseCode = "204", description = "Deleted")
    @APIResponse(responseCode = "404", description = "Unknown episode")
    public void delete(@RestPath String id) {
        require(id);
        episodes.delete(id);
    }

    private EpisodeHandle require(String id) {
        return episodes.find(id).orElseThrow(() -> new UnknownEpisodeException(id));
    }
}
