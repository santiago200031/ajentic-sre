package io.ajentic.sre.api.payload;

import io.ajentic.sre.rl.simulation.Fault;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ServiceSpec;

import java.util.List;

public record ScenarioView(
        String id,
        String title,
        String summary,
        String incidentSummary,
        List<String> services,
        List<String> faults,
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
