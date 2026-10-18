package io.ajentic.sre.rl.simulation;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record Scenario(
        String id,
        String title,
        String summary,
        String incidentSummary,
        List<ServiceSpec> services,
        List<Fault> faults,
        List<Runbook> runbooks,
        int maxSteps) {

    public Scenario {
        services = List.copyOf(services);
        faults = List.copyOf(faults);
        runbooks = List.copyOf(runbooks);
    }

    public String culpritService() {
        return faults.isEmpty() ? services.getFirst().name() : faults.getFirst().service();
    }

    public Set<FaultKind> faultKinds() {
        return faults.stream().map(Fault::kind).collect(Collectors.toUnmodifiableSet());
    }
}
