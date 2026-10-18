package io.ajentic.sre.agent.integration;

import java.util.List;

public record IncidentSummary(String incidentId, String summary, String status, List<String> evidence) {

    public IncidentSummary {
        evidence = List.copyOf(evidence);
    }
}
