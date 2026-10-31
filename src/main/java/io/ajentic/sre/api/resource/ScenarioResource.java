package io.ajentic.sre.api.resource;

import io.ajentic.sre.api.payload.ScenarioView;
import io.ajentic.sre.simulation.ScenarioRepository;
import io.ajentic.sre.simulation.UnknownScenarioException;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestPath;

import java.util.List;

/**
 * Incident scenario definitions and operational runbooks.
 */
@Path("/api/v1/scenarios")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Tag(name = "Scenarios", description = "Incident scenario definitions and operational runbooks")
@RunOnVirtualThread
public class ScenarioResource {

    private final ScenarioRepository scenarios;

    @Inject
    public ScenarioResource(ScenarioRepository scenarios) {
        this.scenarios = scenarios;
    }

    @GET
    @Operation(summary = "List scenarios", description = "Returns every available incident scenario.")
    @APIResponse(responseCode = "200", description = "The scenario list",
            content = @Content(schema = @Schema(implementation = ScenarioView.class)))
    public List<ScenarioView> list() {
        return scenarios.all().stream().map(ScenarioView::from).toList();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a scenario", description = "Returns a single scenario definition.")
    @APIResponse(responseCode = "200", description = "The scenario")
    @APIResponse(responseCode = "404", description = "Unknown scenario")
    public ScenarioView get(@RestPath String id) {
        return scenarios.byId(id)
                .map(ScenarioView::from)
                .orElseThrow(() -> new UnknownScenarioException(id));
    }
}
