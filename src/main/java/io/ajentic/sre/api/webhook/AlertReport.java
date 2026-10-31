package io.ajentic.sre.api.webhook;

import java.time.Instant;

/**
 * The stored intake -> session -> report linkage. Kept per fingerprint so post-mortems can reconstruct what the
 * agent did for a given alert.
 */
public record AlertReport(
        Alert alert,
        String scenarioId,
        String sessionId,
        String answer,
        Instant receivedAt,
        Instant reportedAt,
        boolean duplicate) {
}
