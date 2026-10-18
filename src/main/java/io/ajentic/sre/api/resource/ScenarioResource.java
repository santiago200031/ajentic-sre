package io.ajentic.sre.api.resource;

import io.ajentic.sre.api.payload.ScenarioView;
import io.ajentic.sre.rl.simulation.ScenarioRepository;
import io.ajentic.sre.rl.simulation.UnknownScenarioException;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestPath;

import java.util.List;

@Path("/api/v1/scenarios")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class ScenarioResource {

    private final ScenarioRepository scenarios;

    @Inject
    public ScenarioResource(ScenarioRepository scenarios) {
        this.scenarios = scenarios;
    }

    @GET
    public List<ScenarioView> list() {
        return scenarios.all().stream().map(ScenarioView::from).toList();
    }

    @GET
    @Path("/{id}")
    public ScenarioView get(@RestPath String id) {
        return scenarios.byId(id)
                .map(ScenarioView::from)
                .orElseThrow(() -> new UnknownScenarioException(id));
    }
}
