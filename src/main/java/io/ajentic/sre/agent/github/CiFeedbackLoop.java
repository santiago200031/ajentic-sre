package io.ajentic.sre.agent.github;

import io.ajentic.sre.agent.integration.IncidentSummary;
import io.ajentic.sre.agent.integration.NotificationChannel;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Follow-up loop that re-checks pending remediations against GitHub CI check runs, updates the record, and posts the
 * final verdict (SUCCESS or FAILURE) to {@link NotificationChannel}.
 */
@ApplicationScoped
public class CiFeedbackLoop {

    private static final Logger LOG = Logger.getLogger(CiFeedbackLoop.class);

    private final RemediationStore store;
    private final GitHubClient github;
    private final NotificationChannel notifications;
    private final int maxPollAttempts;

    @Inject
    public CiFeedbackLoop(
            RemediationStore store,
            GitHubClient github,
            NotificationChannel notifications,
            @ConfigProperty(name = "ajentic.github.ci.poll-attempts", defaultValue = "0") int maxPollAttempts) {
        this.store = store;
        this.github = github;
        this.notifications = notifications;
        this.maxPollAttempts = maxPollAttempts;
    }

    @Scheduled(
            every = "${ajentic.github.ci.poll-interval:5s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @RunOnVirtualThread
    void scheduledPoll() {
        if (maxPollAttempts <= 0) {
            return;
        }
        pollPending();
    }

    /**
     * Polls all currently pending remediation records up to {@code maxPollAttempts}. If a run transitions to a
     * terminal verdict (SUCCESS or FAILURE), posts a notification. Returns number of records polled.
     */
    public int pollPending() {
        if (maxPollAttempts <= 0) {
            return 0;
        }
        List<RemediationRecord> pending = store.pending();
        int polled = 0;
        for (RemediationRecord record : pending) {
            if (record.pollAttempts() >= maxPollAttempts) {
                continue;
            }
            try {
                polled++;
                String ref = record.commitSha() != null && !record.commitSha().isBlank()
                        ? record.commitSha()
                        : record.branch();
                GitHubClient.CiStatus status = github.getCiStatus(record.repository(), ref);
                String newStatus = computeStatus(status);
                int attempts = record.pollAttempts() + 1;
                RemediationRecord updated = record.withCiStatus(newStatus, attempts);
                store.update(updated);

                if (!"PENDING".equalsIgnoreCase(newStatus)) {
                    notifications.post(new IncidentSummary(
                            record.repository() + "#" + record.prNumber(),
                            "CI " + newStatus + " for PR #" + record.prNumber() + " (" + record.prUrl() + ")",
                            newStatus,
                            List.of("repository=" + record.repository(),
                                    "branch=" + record.branch(),
                                    "commit=" + record.commitSha(),
                                    "pr=" + record.prUrl())));
                }
            } catch (Exception error) {
                LOG.warnf("Failed to poll CI status for %s on %s: %s", record.repository(), record.branch(),
                        error.getMessage());
            }
        }
        return polled;
    }

    public static String computeStatus(GitHubClient.CiStatus ci) {
        return computeStatusKind(ci).value();
    }

    public static CiStatusKind computeStatusKind(GitHubClient.CiStatus ci) {
        if (ci == null || ci.runs().isEmpty()) {
            return CiStatusKind.PENDING;
        }
        boolean anyPending = ci.runs().stream().anyMatch(r -> !"completed".equalsIgnoreCase(r.status()));
        if (anyPending) {
            return CiStatusKind.PENDING;
        }
        boolean anyFailure = ci.runs().stream().anyMatch(r ->
                "failure".equalsIgnoreCase(r.conclusion())
                        || "timed_out".equalsIgnoreCase(r.conclusion())
                        || "cancelled".equalsIgnoreCase(r.conclusion()));
        if (anyFailure) {
            return CiStatusKind.FAILURE;
        }
        boolean allSuccess = ci.runs().stream().allMatch(r ->
                "success".equalsIgnoreCase(r.conclusion())
                        || "neutral".equalsIgnoreCase(r.conclusion())
                        || "skipped".equalsIgnoreCase(r.conclusion()));
        return allSuccess ? CiStatusKind.SUCCESS : CiStatusKind.PENDING;
    }
}
