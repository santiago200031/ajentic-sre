package io.ajentic.sre.simulation;

import io.ajentic.sre.simulation.action.RemediationKind;
import java.util.List;
import java.util.Set;

public record EnvironmentSpec(
        String scenarioId,
        String title,
        String description,
        int maxSteps,
        Set<TelemetrySignal> signals,
        List<String> runbooks,
        List<RemediationKind> remediations) {

    public EnvironmentSpec {
        signals = Set.copyOf(signals);
        runbooks = List.copyOf(runbooks);
        remediations = List.copyOf(remediations);
    }
}
