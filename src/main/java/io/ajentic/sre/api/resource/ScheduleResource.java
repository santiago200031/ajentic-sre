package io.ajentic.sre.api.resource;

import io.ajentic.sre.api.payload.CreateScheduleRequest;
import io.ajentic.sre.api.payload.ScheduleView;
import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.schedule.drill.IncidentDrillRunner;
import io.ajentic.sre.schedule.engine.Scheduler;
import io.ajentic.sre.schedule.engine.TickReport;
import io.ajentic.sre.schedule.store.ScheduleDefinition;
import io.ajentic.sre.schedule.store.ScheduleNotFoundException;
import io.ajentic.sre.schedule.store.ScheduleStore;
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
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestPath;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Versioned cron scheduler for recurring and manual incident drills.
 */
@Path("/api/v1/schedules")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Tag(name = "Schedules", description = "Versioned cron scheduler for recurring and manual drills")
@RunOnVirtualThread
public class ScheduleResource {

    private final Scheduler scheduler;
    private final ScheduleStore store;
    private final IncidentDrillRunner drills;

    @Inject
    public ScheduleResource(Scheduler scheduler, ScheduleStore store, IncidentDrillRunner drills) {
        this.scheduler = scheduler;
        this.store = store;
        this.drills = drills;
    }

    @GET
    @Operation(summary = "List schedules", description = "Returns every scheduled drill.")
    @APIResponse(responseCode = "200", description = "The schedule list",
            content = @Content(schema = @Schema(implementation = ScheduleView.class)))
    public List<ScheduleView> list() {
        return store.all().stream().map(ScheduleView::from).toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(201)
    @Operation(summary = "Create a schedule", description = "Registers a cron-driven drill for a scenario.")
    @APIResponse(responseCode = "201", description = "Created schedule")
    public ScheduleView create(@Valid CreateScheduleRequest request) {
        ScheduleDefinition definition = scheduler.schedule(request.name(), request.cron(), request.scenarioId());
        return ScheduleView.from(definition);
    }

    @DELETE
    @Path("/{id}")
    @ResponseStatus(204)
    @Operation(summary = "Stop a schedule", description = "Disables a schedule by id.")
    @APIResponse(responseCode = "204", description = "Stopped")
    @APIResponse(responseCode = "404", description = "Unknown schedule")
    public void stop(@RestPath String id) {
        require(id);
        store.stop(id);
    }

    @POST
    @Path("/{id}/run")
    @Operation(summary = "Run a schedule now", description = "Executes a schedule immediately; 204 when nothing ran.")
    @APIResponse(responseCode = "200", description = "The drill result",
            content = @Content(schema = @Schema(implementation = EpisodeResult.class)))
    @APIResponse(responseCode = "204", description = "Nothing ran")
    @APIResponse(responseCode = "404", description = "Unknown schedule")
    public Response run(@RestPath String id) {
        require(id);
        EpisodeResult result = scheduler.runNow(id);
        return result == null ? Response.noContent().build() : Response.ok(result).build();
    }

    @POST
    @Path("/tick")
    @Operation(summary = "Run one scheduler tick", description = "Advances the scheduler by one tick and reports what ran.")
    @APIResponse(responseCode = "200", description = "The tick report")
    public TickReport tick() {
        return scheduler.tick();
    }

    @GET
    @Path("/drills")
    @Operation(summary = "List recent drills", description = "Returns the most recent scheduled drill runs.")
    @APIResponse(responseCode = "200", description = "The drill results",
            content = @Content(schema = @Schema(implementation = EpisodeResult.class)))
    public List<EpisodeResult> drills() {
        return drills.recent();
    }

    private void require(String id) {
        store.byId(id).orElseThrow(() -> new ScheduleNotFoundException(id));
    }
}
