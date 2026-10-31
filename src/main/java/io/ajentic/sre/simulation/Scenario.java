package io.ajentic.sre.simulation;

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

    /** The validated service dependency DAG; throws on unknown references or cycles. */
    public ServiceTopology topology() {
        return ServiceTopology.of(services);
    }

    public String culpritService() {
        return faults.isEmpty() ? services.getFirst().name() : faults.getFirst().service();
    }

    /** The entry service that reports the first symptom, deliberately not the culprit when one is available. */
    public String alertingService() {
        return services.stream()
                .filter(service -> service.kind() == ServiceKind.GATEWAY)
                .map(ServiceSpec::name)
                .findFirst()
                .orElseGet(() -> services.stream()
                        .map(ServiceSpec::name)
                        .filter(name -> !name.equals(culpritService()))
                        .findFirst()
                        .orElseGet(this::culpritService));
    }

    public Set<FaultKind> faultKinds() {
        return faults.stream().map(Fault::kind).collect(Collectors.toUnmodifiableSet());
    }
}
