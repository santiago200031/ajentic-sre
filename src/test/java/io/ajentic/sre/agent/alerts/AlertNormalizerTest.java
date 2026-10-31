package io.ajentic.sre.agent.alerts;

import io.ajentic.sre.agent.mcp.SreAgentPrompts;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AlertNormalizer normalizer = new AlertNormalizer(Optional.of("acme/default-repo"), mapper);

    @Test
    void directJsonPayload() {
        Map<String, Object> payload = Map.of(
                "severity", "ERROR",
                "service", "checkout-service",
                "repository", "acme/checkout-service",
                "branch", "fix/branch",
                "title", "NullPointerException in payment",
                "message", "Payment processing failed",
                "stackTrace", "java.lang.NullPointerException at com.acme.Payment.pay",
                "fingerprint", "fp-direct-1234");

        NormalizedAlert alert = normalizer.normalize(payload);
        assertEquals(AlertSeverity.ERROR, alert.severity());
        assertEquals("checkout-service", alert.service());
        assertEquals("acme/checkout-service", alert.repository());
        assertEquals("fix/branch", alert.branch());
        assertEquals("NullPointerException in payment", alert.title());
        assertEquals("Payment processing failed", alert.message());
        assertEquals("java.lang.NullPointerException at com.acme.Payment.pay", alert.stackTrace());
        assertEquals("fp-direct-1234", alert.fingerprint());
    }

    @Test
    void cloudWatchPayload() {
        Map<String, Object> payload = Map.of(
                "AlarmName", "HighErrorRate-checkout",
                "NewStateReason", "Threshold Crossed: 5xx > 5%",
                "AlarmDescription", "java.lang.RuntimeException at CheckoutService",
                "NewStateValue", "ALARM",
                "repository", "acme/checkout-service");

        NormalizedAlert alert = normalizer.normalize(payload);
        assertEquals(AlertSeverity.ERROR, alert.severity());
        assertEquals("HighErrorRate-checkout", alert.title());
        assertEquals("Threshold Crossed: 5xx > 5%", alert.message());
        assertEquals("java.lang.RuntimeException at CheckoutService", alert.stackTrace());
        assertEquals("acme/checkout-service", alert.repository());
        assertEquals("main", alert.branch());
        assertNotNull(alert.fingerprint());
    }

    @Test
    void azureMonitorPayload() {
        Map<String, Object> payload = Map.of(
                "data", Map.of(
                        "essentials", Map.of(
                                "alertRule", "High Latency - checkout",
                                "description", "P99 latency > 2s",
                                "severity", "Sev1",
                                "targetResourceName", "checkout-service",
                                "repo", "acme/checkout-service"),
                        "alertContext", Map.of(
                                "stackTrace", "java.sql.SQLException: Connection pool timeout")));

        NormalizedAlert alert = normalizer.normalize(payload);
        assertEquals(AlertSeverity.ERROR, alert.severity());
        assertEquals("checkout-service", alert.service());
        assertEquals("acme/checkout-service", alert.repository());
        assertEquals("High Latency - checkout", alert.title());
        assertEquals("P99 latency > 2s", alert.message());
        assertEquals("java.sql.SQLException: Connection pool timeout", alert.stackTrace());
    }

    @Test
    void azureMonitorWarningSeverity() {
        Map<String, Object> payload = Map.of(
                "data", Map.of(
                        "essentials", Map.of(
                                "alertRule", "Disk Warning",
                                "description", "Disk 85% full",
                                "severity", "Sev3")));

        NormalizedAlert alert = normalizer.normalize(payload);
        assertEquals(AlertSeverity.WARNING, alert.severity());
        assertFalse(alert.severity().isAtLeast(AlertSeverity.ERROR));
    }

    @Test
    void alertmanagerPayload() {
        Map<String, Object> payload = Map.of(
                "alerts", List.of(
                        Map.of(
                                "labels", Map.of(
                                        "alertname", "KubePodCrashLooping",
                                        "severity", "critical",
                                        "service", "orders-api",
                                        "github_repo", "acme/orders-service"),
                                "annotations", Map.of(
                                        "summary", "Pod orders-api is crash looping",
                                        "description", "Container killed due to OOM",
                                        "stackTrace", "OutOfMemoryError: Java heap space"))));

        NormalizedAlert alert = normalizer.normalize(payload);
        assertEquals(AlertSeverity.CRITICAL, alert.severity());
        assertEquals("orders-api", alert.service());
        assertEquals("acme/orders-service", alert.repository());
        assertEquals("KubePodCrashLooping", alert.title());
        assertEquals("Pod orders-api is crash looping", alert.message());
        assertEquals("OutOfMemoryError: Java heap space", alert.stackTrace());
    }

    @Test
    void unknownSeverityDefaultsToInfo() {
        Map<String, Object> payload = Map.of(
                "severity", "custom-unknown-level",
                "message", "something happened");

        NormalizedAlert alert = normalizer.normalize(payload);
        assertEquals(AlertSeverity.INFO, alert.severity());
        assertFalse(alert.severity().isAtLeast(AlertSeverity.ERROR));
    }

    @Test
    void emptyPayloadDoesNotThrow() {
        NormalizedAlert alert = normalizer.normalize(Map.of());
        assertEquals(AlertSeverity.INFO, alert.severity());
        assertEquals("acme/default-repo", alert.repository());
        assertEquals("main", alert.branch());
        assertNotNull(alert.fingerprint());
    }

    @Test
    void fallbackToDefaultRepoWhenUnspecified() {
        Map<String, Object> payload = Map.of(
                "severity", "ERROR",
                "title", "Bug",
                "message", "Error in system");

        NormalizedAlert alert = normalizer.normalize(payload);
        assertEquals("acme/default-repo", alert.repository());
    }

    @Test
    void allFourPayloadShapesNormalizeToSameRemediationPrompt() {
        String sharedFingerprint = "12345678abcdef";
        String repo = "acme/checkout-service";
        String branch = "main";
        String title = "NullPointerException in Checkout";
        String message = "Null pointer in processCheckout";
        String trace = "java.lang.NullPointerException at com.acme.Checkout.process(Checkout.java:55)";

        // 1. Direct
        Map<String, Object> direct = Map.of(
                "severity", "ERROR",
                "repository", repo,
                "branch", branch,
                "title", title,
                "message", message,
                "stackTrace", trace,
                "fingerprint", sharedFingerprint);

        // 2. CloudWatch
        Map<String, Object> cloudwatch = Map.of(
                "NewStateValue", "ALARM",
                "AlarmName", title,
                "NewStateReason", message,
                "AlarmDescription", trace,
                "repository", repo,
                "branch", branch,
                "fingerprint", sharedFingerprint);

        // 3. Azure
        Map<String, Object> azure = Map.of(
                "data", Map.of(
                        "essentials", Map.of(
                                "alertRule", title,
                                "description", message,
                                "severity", "Sev1",
                                "repo", repo,
                                "branch", branch),
                        "alertContext", trace),
                "fingerprint", sharedFingerprint);

        // 4. Alertmanager
        Map<String, Object> alertmanager = Map.of(
                "alerts", List.of(Map.of(
                        "labels", Map.of(
                                "alertname", title,
                                "severity", "error",
                                "github_repo", repo,
                                "branch", branch),
                        "annotations", Map.of(
                                "summary", message,
                                "stackTrace", trace))),
                "fingerprint", sharedFingerprint);

        NormalizedAlert aDirect = normalizer.normalize(direct);
        NormalizedAlert aCloudwatch = normalizer.normalize(cloudwatch);
        NormalizedAlert aAzure = normalizer.normalize(azure);
        NormalizedAlert aAlertmanager = normalizer.normalize(alertmanager);

        String promptDirect = SreAgentPrompts.buildCodeRemediationPrompt(
                aDirect.repository(), aDirect.branch(), aDirect.title(), aDirect.message(), aDirect.stackTrace(), aDirect.fingerprint());
        String promptCloudwatch = SreAgentPrompts.buildCodeRemediationPrompt(
                aCloudwatch.repository(), aCloudwatch.branch(), aCloudwatch.title(), aCloudwatch.message(), aCloudwatch.stackTrace(), aCloudwatch.fingerprint());
        String promptAzure = SreAgentPrompts.buildCodeRemediationPrompt(
                aAzure.repository(), aAzure.branch(), aAzure.title(), aAzure.message(), aAzure.stackTrace(), aAzure.fingerprint());
        String promptAlertmanager = SreAgentPrompts.buildCodeRemediationPrompt(
                aAlertmanager.repository(), aAlertmanager.branch(), aAlertmanager.title(), aAlertmanager.message(), aAlertmanager.stackTrace(), aAlertmanager.fingerprint());

        assertEquals(promptDirect, promptCloudwatch);
        assertEquals(promptDirect, promptAzure);
        assertEquals(promptDirect, promptAlertmanager);
    }
}
