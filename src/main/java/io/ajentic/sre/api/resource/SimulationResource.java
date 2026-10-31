package io.ajentic.sre.api.resource;

import io.ajentic.sre.api.mapper.ActionMapper;
import io.ajentic.sre.api.payload.ActionRequest;
import io.ajentic.sre.api.payload.CreateSimulationRequest;
import io.ajentic.sre.api.payload.RunSimulationRequest;
import io.ajentic.sre.api.payload.SimulateRequest;
import io.ajentic.sre.api.payload.SimulationView;
import io.ajentic.sre.api.payload.StepResultView;
import io.ajentic.sre.simulation.StepResult;
import io.ajentic.sre.simulation.drill.SimulationHandle;
import io.ajentic.sre.simulation.drill.SimulationResult;
import io.ajentic.sre.simulation.drill.SimulationService;
import io.ajentic.sre.simulation.drill.UnknownSimulationException;
import io.ajentic.sre.simulation.playbook.PlaybookFactory;
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
 * Deterministic incident simulation and playbook evaluation. Simulations are bounded by a Caffeine cache;
 * {@link #simulate(SimulateRequest)} runs a one-shot simulation without leaving state behind.
 */
@Path("/api/v1/simulations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Tag(name = "Simulations", description = "Deterministic incident simulation and playbook evaluation")
@RunOnVirtualThread
public class SimulationResource {

    private final SimulationService simulations;
    private final PlaybookFactory playbooks;

    @Inject
    public SimulationResource(SimulationService simulations, PlaybookFactory playbooks) {
        this.simulations = simulations;
        this.playbooks = playbooks;
    }

    @POST
    @ResponseStatus(201)
    @Operation(summary = "Start a simulation", description = "Initialises a simulation for a scenario and seed.")
    @APIResponse(responseCode = "201", description = "Started simulation")
    public SimulationView start(@Valid CreateSimulationRequest request) {
        long seed = request.seed() == null ? 42L : request.seed();
        SimulationHandle handle = simulations.start(request.scenarioId(), seed);
        return SimulationView.from(handle);
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a simulation", description = "Returns the simulation and its current state.")
    @APIResponse(responseCode = "200", description = "The simulation")
    @APIResponse(responseCode = "404", description = "Unknown simulation")
    public SimulationView get(@RestPath String id) {
        return SimulationView.from(require(id));
    }

    @POST
    @Path("/{id}/actions")
    @Operation(summary = "Step the simulation environment", description = "Applies a single manual action to the simulation environment.")
    @APIResponse(responseCode = "200", description = "The step result")
    @APIResponse(responseCode = "404", description = "Unknown simulation")
    public StepResultView step(@RestPath String id, @Valid ActionRequest request) {
        StepResult result = require(id).environment().step(ActionMapper.toAction(request));
        return StepResultView.from(result);
    }

    @POST
    @Path("/{id}/run")
    @Operation(summary = "Run a simulation", description = "Runs the simulation to completion against a named playbook.")
    @APIResponse(responseCode = "200", description = "The simulation result")
    @APIResponse(responseCode = "404", description = "Unknown simulation")
    public SimulationResult run(@RestPath String id, RunSimulationRequest request) {
        require(id);
        return simulations.run(id, playbooks.byName(request == null ? null : request.playbook()));
    }

    @POST
    @Path("/simulate")
    @Operation(summary = "One-shot simulation", description = "Starts a simulation for a scenario, runs it to completion against a playbook, and returns the result. No simulation is retained.")
    @APIResponse(responseCode = "200", description = "The simulation result")
    public SimulationResult simulate(@Valid SimulateRequest request) {
        long seed = request.seed() == null ? 42L : request.seed();
        SimulationHandle handle = simulations.start(request.scenarioId(), seed);
        try {
            return simulations.run(handle.id(), playbooks.byName(request.playbook()));
        } finally {
            simulations.delete(handle.id());
        }
    }

    @DELETE
    @Path("/{id}")
    @ResponseStatus(204)
    @Operation(summary = "Delete a simulation", description = "Removes a simulation.")
    @APIResponse(responseCode = "204", description = "Deleted")
    @APIResponse(responseCode = "404", description = "Unknown simulation")
    public void delete(@RestPath String id) {
        require(id);
        simulations.delete(id);
    }

    private SimulationHandle require(String id) {
        return simulations.find(id).orElseThrow(() -> new UnknownSimulationException(id));
    }
}
