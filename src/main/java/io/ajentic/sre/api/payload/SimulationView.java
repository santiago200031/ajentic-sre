package io.ajentic.sre.api.payload;

import io.ajentic.sre.simulation.drill.SimulationHandle;
import io.ajentic.sre.simulation.drill.SimulationResult;
import io.ajentic.sre.simulation.Observation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * View of a simulation: its id, the current observation, whether it has completed, and its final result if present.
 */
@Schema(description = "View of a simulation.")
public record SimulationView(
        @Schema(description = "Simulation id.", example = "s1a2b3c4-...")
        String id,

        @Schema(description = "The current environment observation.")
        Observation observation,

        @Schema(description = "Whether the simulation has completed.", example = "false")
        boolean completed,

        @Schema(description = "The final simulation result, present once the simulation completes.")
        SimulationResult result) {

    public static SimulationView from(SimulationHandle handle) {
        return new SimulationView(
                handle.id(),
                handle.environment().observe(),
                handle.result().isPresent(),
                handle.result().orElse(null));
    }
}
