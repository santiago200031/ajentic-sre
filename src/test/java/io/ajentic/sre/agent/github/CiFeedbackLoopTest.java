package io.ajentic.sre.agent.github;

import io.ajentic.sre.agent.integration.IncidentSummary;
import io.ajentic.sre.agent.integration.NotificationChannel;
import io.ajentic.sre.test.WireMockLlmTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@WithTestResource(WireMockLlmTestResource.class)
class CiFeedbackLoopTest {

    @Inject
    RemediationStore store;

    @Inject
    GitHubClient github;

    @BeforeEach
    void setUp() {
        store.clear();
    }

    @Test
    void pendingCheckRunYieldsProcessedWithCiStatusPendingAndNoLongWait() {
        long start = System.currentTimeMillis();

        Response response = given()
                .contentType("application/json")
                .body(Map.of(
                        "severity", "ERROR",
                        "repository", "acme/checkout-service",
                        "title", "NullPointerException in checkout",
                        "message", "NullPointerException: order cannot be null",
                        "stackTrace", "java.lang.NullPointerException at com.acme.Checkout.process"))
                .when().post("/api/v1/webhooks/alerts")
                .then()
                .statusCode(200)
                .body("status", is("PROCESSED"))
                .body("ciStatus", is("PENDING"))
                .body("remediation.ciStatus", is("PENDING"))
                .body("remediation.prNumber", is(42))
                .body("answer", containsString("CI pending"))
                .extract().response();

        long duration = System.currentTimeMillis() - start;
        // Verify it returned immediately without waiting for minutes
        assertTrue(duration < 5000, "Turn took " + duration + "ms, expected < 5000ms");

        RemediationRecord record = store.findLatestForRepo("acme/checkout-service").orElseThrow();
        assertEquals("PENDING", record.ciStatus());
        assertEquals(42, record.prNumber());
        assertEquals("https://github.com/acme/checkout-service/pull/42", record.prUrl());
    }

    @Test
    void secondPollFlipsRecordToFailureAndEmitsOneNotification() {
        List<IncidentSummary> notifications = new ArrayList<>();
        NotificationChannel channel = notifications::add;

        // Create a pending remediation with commit def456 (which returns failed checks in WireMock)
        RemediationRecord pending = new RemediationRecord(
                "rec-123",
                "acme/checkout-service",
                "ajentic/fix-1234",
                "def456",
                42,
                "https://github.com/acme/checkout-service/pull/42",
                "PENDING",
                java.time.Instant.now(),
                0);
        store.record(pending);

        CiFeedbackLoop loop = new CiFeedbackLoop(store, github, channel, 3);
        int polled = loop.pollPending();

        assertEquals(1, polled);
        RemediationRecord updated = store.findById("rec-123").orElseThrow();
        assertEquals("FAILURE", updated.ciStatus());
        assertEquals(1, updated.pollAttempts());

        assertEquals(1, notifications.size());
        IncidentSummary notification = notifications.getFirst();
        assertEquals("FAILURE", notification.status());
        assertTrue(notification.summary().contains("CI FAILURE"));
        assertTrue(notification.summary().contains("#42"));
    }

    @Test
    void disabledPollingDoesNothing() {
        List<IncidentSummary> notifications = new ArrayList<>();
        NotificationChannel channel = notifications::add;

        RemediationRecord pending = new RemediationRecord(
                "rec-disabled",
                "acme/checkout-service",
                "ajentic/fix-1234",
                "def456",
                42,
                "https://github.com/acme/checkout-service/pull/42",
                "PENDING",
                java.time.Instant.now(),
                0);
        store.record(pending);

        // maxPollAttempts = 0 (disabled)
        CiFeedbackLoop loop = new CiFeedbackLoop(store, github, channel, 0);
        int polled = loop.pollPending();

        assertEquals(0, polled);
        RemediationRecord record = store.findById("rec-disabled").orElseThrow();
        assertEquals("PENDING", record.ciStatus());
        assertEquals(0, record.pollAttempts());
        assertTrue(notifications.isEmpty());
    }

    @Test
    void computesCiStatusFromRuns() {
        // Empty runs -> PENDING
        GitHubClient.CiStatus empty = new GitHubClient.CiStatus(0, List.of());
        assertEquals(CiStatusKind.PENDING, CiFeedbackLoop.computeStatusKind(empty));

        // In-progress -> PENDING
        GitHubClient.CiStatus inProgress = new GitHubClient.CiStatus(1, List.of(
                new GitHubClient.CheckRun("build", "in_progress", null, "https://url")));
        assertEquals(CiStatusKind.PENDING, CiFeedbackLoop.computeStatusKind(inProgress));

        // All completed with success -> SUCCESS
        GitHubClient.CiStatus success = new GitHubClient.CiStatus(2, List.of(
                new GitHubClient.CheckRun("build", "completed", "success", "https://url"),
                new GitHubClient.CheckRun("test", "completed", "success", "https://url")));
        assertEquals(CiStatusKind.SUCCESS, CiFeedbackLoop.computeStatusKind(success));

        // Mixed success and failure -> FAILURE
        GitHubClient.CiStatus failure = new GitHubClient.CiStatus(2, List.of(
                new GitHubClient.CheckRun("build", "completed", "success", "https://url"),
                new GitHubClient.CheckRun("test", "completed", "failure", "https://url")));
        assertEquals(CiStatusKind.FAILURE, CiFeedbackLoop.computeStatusKind(failure));

        // Timed out -> FAILURE
        GitHubClient.CiStatus timedOut = new GitHubClient.CiStatus(1, List.of(
                new GitHubClient.CheckRun("test", "completed", "timed_out", "https://url")));
        assertEquals(CiStatusKind.FAILURE, CiFeedbackLoop.computeStatusKind(timedOut));
    }

    @Test
    void maxAttemptsExhaustedFlipsRecordToTimedOutAndEmitsNotification() {
        List<IncidentSummary> notifications = new ArrayList<>();
        NotificationChannel channel = notifications::add;

        // Commit abc123 returns PENDING check runs in WireMock
        RemediationRecord pending = new RemediationRecord(
                "rec-timeout",
                "acme/checkout-service",
                "ajentic/fix-1234",
                "abc123",
                42,
                "https://github.com/acme/checkout-service/pull/42",
                "PENDING",
                java.time.Instant.now(),
                0);
        store.record(pending);

        // maxPollAttempts = 1 -> First poll increments attempts to 1 and immediately marks TIMED_OUT
        CiFeedbackLoop loop = new CiFeedbackLoop(store, github, channel, 1);
        int polled = loop.pollPending();

        assertEquals(1, polled);
        RemediationRecord updated = store.findById("rec-timeout").orElseThrow();
        assertEquals(CiStatusKind.TIMED_OUT.value(), updated.ciStatus());
        assertEquals(1, updated.pollAttempts());
        assertTrue(updated.ciStatus().equals(CiStatusKind.TIMED_OUT.value()));
        assertEquals(1, notifications.size());
        assertTrue(notifications.getFirst().summary().contains("TIMED_OUT"));

        // Next poll should do 0 because it's no longer pending
        assertEquals(0, loop.pollPending());
    }

    @Test
    void exceptionDuringPollIncrementsAttemptsAndReachesFailure() {
        List<IncidentSummary> notifications = new ArrayList<>();
        NotificationChannel channel = notifications::add;

        // unknown-repo will throw an error against WireMock (404)
        RemediationRecord pending = new RemediationRecord(
                "rec-err",
                "unknown-org/missing-repo",
                "ajentic/fix-1234",
                "abc123",
                99,
                "https://github.com/unknown-org/missing-repo/pull/99",
                "PENDING",
                java.time.Instant.now(),
                0);
        store.record(pending);

        CiFeedbackLoop loop = new CiFeedbackLoop(store, github, channel, 2);
        // Attempt 1: caught exception, increments attempt to 1, status stays PENDING
        int polled = loop.pollPending();
        assertEquals(1, polled);
        RemediationRecord attempt1 = store.findById("rec-err").orElseThrow();
        assertEquals(1, attempt1.pollAttempts());
        assertEquals(CiStatusKind.PENDING.value(), attempt1.ciStatus());
        assertTrue(notifications.isEmpty());

        // Attempt 2: caught exception, increments attempt to 2 (= maxAttempts), transitions to FAILURE and notifies
        polled = loop.pollPending();
        assertEquals(1, polled);
        RemediationRecord attempt2 = store.findById("rec-err").orElseThrow();
        assertEquals(2, attempt2.pollAttempts());
        assertEquals(CiStatusKind.FAILURE.value(), attempt2.ciStatus());
        assertEquals(1, notifications.size());
        assertTrue(notifications.getFirst().summary().contains("FAILURE"));
    }
}
