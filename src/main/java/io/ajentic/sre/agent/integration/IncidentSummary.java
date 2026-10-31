package io.ajentic.sre.agent.integration;

import java.util.List;

public record IncidentSummary(
        String incidentId,
        String summary,
        String status,
        List<String> evidence,
        String rootCause,
        String prLink,
        String ciStatus,
        String episodeId) {

    public IncidentSummary {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (episodeId == null || episodeId.isBlank()) {
            episodeId = incidentId;
        }
        if (ciStatus == null || ciStatus.isBlank()) {
            ciStatus = status;
        }
        if (rootCause == null || rootCause.isBlank()) {
            rootCause = summary;
        }
        if (prLink == null || prLink.isBlank()) {
            for (String e : evidence) {
                if (e != null && e.startsWith("pr=")) {
                    prLink = e.substring(3);
                    break;
                }
            }
        }
    }

    public IncidentSummary(String incidentId, String summary, String status, List<String> evidence) {
        this(incidentId, summary, status, evidence, null, null, null, null);
    }
}
