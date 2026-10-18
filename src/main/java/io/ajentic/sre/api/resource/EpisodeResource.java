package io.ajentic.sre.api.resource;

import io.ajentic.sre.api.mapper.ActionMapper;
import io.ajentic.sre.api.payload.ActionRequest;
import io.ajentic.sre.api.payload.CreateEpisodeRequest;
import io.ajentic.sre.api.payload.EpisodeView;
import io.ajentic.sre.api.payload.RunEpisodeRequest;
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

@Path("/api/v1/episodes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
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
    public EpisodeView start(@Valid CreateEpisodeRequest request) {
        long seed = request.seed() == null ? 42L : request.seed();
        EpisodeHandle handle = episodes.start(request.scenarioId(), seed);
        return EpisodeView.from(handle);
    }

    @GET
    @Path("/{id}")
    public EpisodeView get(@RestPath String id) {
        return EpisodeView.from(require(id));
    }

    @POST
    @Path("/{id}/actions")
    public StepResultView step(@RestPath String id, @Valid ActionRequest request) {
        StepResult result = require(id).environment().step(ActionMapper.toAction(request));
        return StepResultView.from(result);
    }

    @POST
    @Path("/{id}/run")
    public EpisodeResult run(@RestPath String id, RunEpisodeRequest request) {
        require(id);
        return episodes.run(id, policies.byName(request == null ? null : request.policy()));
    }

    @DELETE
    @Path("/{id}")
    @ResponseStatus(204)
    public void delete(@RestPath String id) {
        require(id);
        episodes.delete(id);
    }

    private EpisodeHandle require(String id) {
        return episodes.find(id).orElseThrow(() -> new UnknownEpisodeException(id));
    }
}
