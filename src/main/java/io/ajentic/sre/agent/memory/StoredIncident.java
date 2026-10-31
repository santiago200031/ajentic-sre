package io.ajentic.sre.agent.memory;

import java.time.Instant;

/**
 * One completed episode, persisted as a JSON line in {@code .ajentic/incidents.jsonl}. Text fields are masked
 * before they hit disk.
 */
public record StoredIncident(
        String episodeId,
        String scenarioId,
        String title,
        String faultKind,
        String service,
        String diagnosis,
        String remediation,
        boolean solved,
        boolean diagnosed,
        double reward,
        int steps,
        Instant completedAt) {
}
