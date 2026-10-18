package io.ajentic.sre.api.resource;

import io.ajentic.sre.api.payload.CreateScheduleRequest;
import io.ajentic.sre.api.payload.ScheduleView;
import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.schedule.drill.IncidentDrillRunner;
import io.ajentic.sre.schedule.engine.ScheduledJob;
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

import java.util.List;

@Path("/api/v1/schedules")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
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
    public List<ScheduleView> list() {
        return store.all().stream().map(ScheduleView::from).toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(201)
    public ScheduleView create(@Valid CreateScheduleRequest request) {
        ScheduledJob job = request.scenarioId() == null || request.scenarioId().isBlank()
                ? context -> { }
                : drills.job(request.scenarioId());
        ScheduleDefinition definition = scheduler.schedule(request.name(), request.cron(), job);
        return ScheduleView.from(definition);
    }

    @DELETE
    @Path("/{id}")
    @ResponseStatus(204)
    public void stop(@RestPath String id) {
        require(id);
        store.stop(id);
    }

    @POST
    @Path("/{id}/run")
    public Response run(@RestPath String id) {
        require(id);
        scheduler.runNow(id);
        List<EpisodeResult> recent = drills.recent();
        if (recent.isEmpty()) {
            return Response.noContent().build();
        }
        return Response.ok(recent.getLast()).build();
    }

    @POST
    @Path("/tick")
    public TickReport tick() {
        return scheduler.tick();
    }

    @GET
    @Path("/drills")
    public List<EpisodeResult> drills() {
        return drills.recent();
    }

    private void require(String id) {
        store.byId(id).orElseThrow(() -> new ScheduleNotFoundException(id));
    }
}
