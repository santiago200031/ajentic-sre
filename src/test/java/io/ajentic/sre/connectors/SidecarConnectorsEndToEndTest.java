package io.ajentic.sre.connectors;

import io.ajentic.sre.agent.integration.IncidentSummary;
import io.ajentic.sre.agent.integration.SidecarClient;
import io.ajentic.sre.agent.integration.SidecarNotificationChannel;
import io.ajentic.sre.simulation.ObservedSignal;
import io.ajentic.sre.simulation.StepResult;
import io.ajentic.sre.simulation.TelemetrySignal;
import io.ajentic.sre.simulation.action.ApplyRemediation;
import io.ajentic.sre.simulation.action.InspectRecentChanges;
import io.ajentic.sre.simulation.action.QueryTelemetry;
import io.ajentic.sre.simulation.action.RemediationKind;
import io.ajentic.sre.simulation.environment.sidecar.SidecarEnvironment;
import io.ajentic.sre.simulation.scorecard.IncidentScorecard;
import io.ajentic.sre.simulation.Scenario;
import io.ajentic.sre.simulation.ScenarioLibrary;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SidecarConnectorsEndToEndTest {

    @Inject
    SidecarInternalResource sidecarResource;

    @Inject
    SidecarClient sidecarClient;

    private SidecarNotificationChannel sidecarChannel;

    private final Scenario scenario = new ScenarioLibrary().byId("orders-bad-deployment").orElseThrow();
    private static final Path AUDIT_LOG = Path.of(".ajentic/audit.jsonl");

    @BeforeEach
    void cleanAudit() throws IOException {
        sidecarChannel = new SidecarNotificationChannel(sidecarClient);
        Files.deleteIfExists(AUDIT_LOG);
    }

    @Test
    void securityValidatesSignaturesAndPreventsReplays() {
        SidecarSecurity sec = new SidecarSecurity("test-shared-secret");
        String now = Instant.now().toString();
        String nonce1 = UUID.randomUUID().toString();
        String body = "{\"service\":\"checkout-api\"}";

        String sig1 = sec.sign(now, nonce1, body);

        // 1. Valid signature accepted
        assertTrue(sec.verify(now, nonce1, sig1, body));

        // 2. Replay of same nonce is rejected
        assertFalse(sec.verify(now, nonce1, sig1, body));

        // 3. Bad signature rejected and does not burn nonce
        String nonce2 = UUID.randomUUID().toString();
        assertFalse(sec.verify(now, nonce2, "bad-sig", body));
        String validSig2 = sec.sign(now, nonce2, body);
        assertTrue(sec.verify(now, nonce2, validSig2, body), "Legitimate request must succeed after unauthenticated attempt");
        // Replaying validSig2 is now rejected
        assertFalse(sec.verify(now, nonce2, validSig2, body));

        // 4. Stale timestamp rejected (> 5 min old)
        String staleTime = Instant.now().minusSeconds(600).toString();
        String nonce3 = UUID.randomUUID().toString();
        String sig3 = sec.sign(staleTime, nonce3, body);
        assertFalse(sec.verify(staleTime, nonce3, sig3, body));

        // 5. Future timestamp rejected (> 5 min ahead)
        String futureTime = Instant.now().plusSeconds(600).toString();
        String nonce4 = UUID.randomUUID().toString();
        String sig4 = sec.sign(futureTime, nonce4, body);
        assertFalse(sec.verify(futureTime, nonce4, sig4, body));

        // 6. Blank / null nonces and timestamps rejected
        assertFalse(sec.verify(null, nonce1, sig1, body));
        assertFalse(sec.verify(now, "", sig1, body));
        assertFalse(sec.verify(now, nonce1, null, body));
    }

    @Test
    void internalResourceRejectsUnauthorizedCalls() {
        SidecarDto.TelemetryRequest req = new SidecarDto.TelemetryRequest("orders-api", "METRICS");
        assertThrows(WebApplicationException.class, () ->
                sidecarResource.queryTelemetry("aws", Instant.now().toString(), "nonce-1", "invalid-sig", req));
    }

    @Test
    void sidecarClientExecutesRemediationAndLogsAuditEntry() throws IOException {
        SidecarDto.RemediationResponse resp = sidecarClient.remediate("aws", "orders-api", "RESTART", "test-agent");
        assertTrue(resp.success(), resp.message());

        assertTrue(Files.exists(AUDIT_LOG));
        String logContent = Files.readString(AUDIT_LOG);
        assertTrue(logContent.contains("\"actor\":\"test-agent\""));
        assertTrue(logContent.contains("\"tool\":\"RESTART\""));
        assertTrue(logContent.contains("\"target\":\"orders-api@aws\""));
        assertTrue(logContent.contains("\"result\":\"SUCCESS\""));

        // Verify masking in audit log for sensitive identifiers
        SidecarAuditLogger auditLogger = new SidecarAuditLogger();
        auditLogger.recordMutation(
                "agent",
                "RESTART",
                "arn:aws:ecs:us-east-1:123456789012:service/orders-api",
                "SUCCESS",
                "Restarted service using token ghp_012345678901234567890123456789012345 on 192.168.1.100");

        String updatedLog = Files.readString(AUDIT_LOG);
        assertFalse(updatedLog.contains("ghp_012345678901234567890123456789012345"));
        assertFalse(updatedLog.contains("192.168.1.100"));
        assertTrue(updatedLog.contains("[[API_KEY_") || updatedLog.contains("[[TOKEN_") || updatedLog.contains("[[IP_"));
    }

    @Test
    void sidecarEnvironmentStepsAndQueriesTelemetry() {
        SidecarEnvironment env = new SidecarEnvironment(
                scenario,
                new IncidentScorecard(),
                sidecarClient,
                "aws",
                true);

        List<ObservedSignal> signals = env.signals(TelemetrySignal.METRICS, "orders-api");
        assertFalse(signals.isEmpty());

        StepResult result = env.step(new InspectRecentChanges("orders-api", 60));
        assertTrue(result.observation().feedback().contains("Sidecar returned"));

        StepResult remResult = env.step(new ApplyRemediation(RemediationKind.RESTART, "orders-api"));
        assertTrue(remResult.observation().feedback().contains("Executed RESTART"));
    }

    @Test
    void sidecarEnvironmentGracefullyFailsClosedWhenUnreachable() {
        // Dummy client with a client pointing to bad port
        SidecarConnectorClient failingClient = new SidecarConnectorClient() {
            @Override
            public SidecarDto.TelemetryResponse queryTelemetry(String cloud, String ts, String n, String sig, SidecarDto.TelemetryRequest r) {
                throw new RuntimeException("Connection refused");
            }

            @Override
            public SidecarDto.ChangesResponse inspectChanges(String cloud, String ts, String n, String sig, SidecarDto.ChangesRequest r) {
                throw new RuntimeException("Connection refused");
            }

            @Override
            public SidecarDto.RemediationResponse remediate(String cloud, String ts, String n, String sig, SidecarDto.RemediationRequest r) {
                throw new RuntimeException("Connection refused");
            }

            @Override
            public SidecarDto.NotifyResponse notify(String provider, String ts, String n, String sig, SidecarDto.NotifyRequest r) {
                throw new RuntimeException("Connection refused");
            }
        };

        SidecarClient mockClient = new SidecarClient(failingClient, "sec");
        SidecarEnvironment env = new SidecarEnvironment(
                scenario,
                new IncidentScorecard(),
                mockClient,
                "aws",
                true);

        StepResult stepResult = env.step(new QueryTelemetry(TelemetrySignal.METRICS, "orders-api"));
        assertTrue(stepResult.observation().feedback().contains("Sidecar connector unavailable"), stepResult.observation().feedback());
        assertFalse(stepResult.observation().terminal());
    }

    @Test
    void sidecarNotificationChannelDeliversSummary() {
        IncidentSummary summary = new IncidentSummary(
                "INC-1234",
                "High error rate on orders-api",
                "OPEN",
                List.of("5xx spike"),
                "NullPointerException in controller",
                "https://github.com/org/repo/pull/42",
                "SUCCESS",
                "ep-1234");

        sidecarChannel.post(summary);
        assertEquals("sidecar", sidecarChannel.name());
    }
}
