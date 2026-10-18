package io.ajentic.sre.rl;

import io.ajentic.sre.rl.action.RemediationKind;
import java.util.List;
import java.util.Map;

public record Observation(
        String scenarioId,
        String incidentSummary,
        int step,
        int maxSteps,
        boolean terminal,
        boolean resolved,
        boolean diagnosed,
        String feedback,
        List<ObservedSignal> signals,
        List<String> availableRunbooks,
        List<RemediationKind> availableRemediations,
        Map<String, ServiceHealth> serviceHealth,
        double cumulativeReward) {

    public Observation {
        signals = List.copyOf(signals);
        availableRunbooks = List.copyOf(availableRunbooks);
        availableRemediations = List.copyOf(availableRemediations);
        serviceHealth = Map.copyOf(serviceHealth);
    }

    public Observation withCumulativeReward(double reward) {
        return new Observation(
                scenarioId,
                incidentSummary,
                step,
                maxSteps,
                terminal,
                resolved,
                diagnosed,
                feedback,
                signals,
                availableRunbooks,
                availableRemediations,
                serviceHealth,
                reward);
    }
}
