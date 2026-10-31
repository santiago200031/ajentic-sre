package io.ajentic.sre.agent.github;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable record of an automated code remediation turn: the repository, target branch, commit SHA, PR number and
 * URL, and current CI status snapshot.
 */
public record RemediationRecord(
        String id,
        String repository,
        String branch,
        String commitSha,
        int prNumber,
        String prUrl,
        String ciStatus,
        Instant createdAt,
        int pollAttempts) {

    public RemediationRecord(String repository, String branch, String commitSha, int prNumber, String prUrl,
                             String ciStatus) {
        this(UUID.randomUUID().toString(), repository, branch, commitSha, prNumber, prUrl, ciStatus, Instant.now(), 0);
    }

    public RemediationRecord withCiStatus(String newStatus, int attempts) {
        return new RemediationRecord(id, repository, branch, commitSha, prNumber, prUrl, newStatus, createdAt, attempts);
    }
}
