package io.ajentic.sre.rl.simulation.pack;

import io.ajentic.sre.rl.simulation.Fault;
import io.ajentic.sre.rl.simulation.FaultKind;
import io.ajentic.sre.rl.simulation.Runbook;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ServiceKind;
import io.ajentic.sre.rl.simulation.ServiceSpec;
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
        return new Scenario(
                id,
                title,
                summary,
                incidentSummary,
                services.stream().map(s -> new ServiceSpec(s.name(), ServiceKind.valueOf(s.kind()), s.replicas())).toList(),
                faults.stream().map(f -> new Fault(FaultKind.valueOf(f.kind().toUpperCase()), f.service(), f.severity())).toList(),
                runbooks.stream().map(r -> new Runbook(r.id(), r.title(), r.body())).toList(),
                maxSteps);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServicePack(String name, String kind, int replicas) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FaultPack(String kind, String service, double severity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunbookPack(String id, String title, String body) {
    }
}
