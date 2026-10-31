package io.ajentic.sre.simulation.pack;

import io.ajentic.sre.simulation.Fault;
import io.ajentic.sre.simulation.FaultKind;
import io.ajentic.sre.simulation.Runbook;
import io.ajentic.sre.simulation.Scenario;
import io.ajentic.sre.simulation.ServiceKind;
import io.ajentic.sre.simulation.ServiceSpec;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** External scenario pack schema (YAML/JSON). Mirrors {@link Scenario} so a pack maps onto it one to one. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioPack(
        String id,
        String title,
        String summary,
        String incidentSummary,
        List<ServicePack> services,
        List<FaultPack> faults,
        List<RunbookPack> runbooks,
        int maxSteps) {

    public Scenario toScenario() {
        Scenario scenario = new Scenario(
                id,
                title,
                summary,
                incidentSummary,
                services.stream().map(s -> new ServiceSpec(
                        s.name(), ServiceKind.valueOf(s.kind()), s.replicas(),
                        s.dependencies() == null ? List.of() : s.dependencies())).toList(),
                faults.stream().map(f -> new Fault(FaultKind.valueOf(f.kind().toUpperCase()), f.service(), f.severity())).toList(),
                runbooks.stream().map(r -> new Runbook(r.id(), r.title(), r.body())).toList(),
                maxSteps);
        scenario.topology();
        return scenario;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServicePack(String name, String kind, int replicas, List<String> dependencies) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FaultPack(String kind, String service, double severity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunbookPack(String id, String title, String body) {
    }
}
