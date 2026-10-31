package io.ajentic.sre.api.payload;

import io.ajentic.sre.simulation.Fault;
import io.ajentic.sre.simulation.Scenario;
import io.ajentic.sre.simulation.ServiceSpec;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * View of an incident scenario: its identity, description, the services and faults involved, and the step budget.
 */
@Schema(description = "View of an incident scenario.")
public record ScenarioView(
        @Schema(description = "Scenario id.", example = "checkout-latency-db-pool")
        String id,

        @Schema(description = "Scenario title.", example = "Checkout latency from a saturated database pool")
        String title,

        @Schema(description = "Scenario summary.")
        String summary,

        @Schema(description = "The incident summary presented to the agent.")
        String incidentSummary,

        @Schema(description = "Service names in the scenario.")
        List<String> services,

        @Schema(description = "Fault kinds in the scenario.")
        List<String> faults,

        @Schema(description = "Maximum agent steps.", example = "12")
        int maxSteps) {

    public static ScenarioView from(Scenario scenario) {
        return new ScenarioView(
                scenario.id(),
                scenario.title(),
                scenario.summary(),
                scenario.incidentSummary(),
                scenario.services().stream().map(ServiceSpec::name).toList(),
                scenario.faults().stream().map(Fault::kind).map(Enum::name).toList(),
                scenario.maxSteps());
    }
}
