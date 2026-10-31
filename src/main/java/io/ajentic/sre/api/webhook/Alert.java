package io.ajentic.sre.api.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Normalized view of an inbound alert, regardless of which provider sent it. The fingerprint is a SHA-256 of the
 * provider plus its stable identifier, so repeat alerts for the same incident coalesce.
 */
public record Alert(String source, String service, String summary, String severity, String fingerprint) {

    public static final String ALERTMANAGER = "ALERTMANAGER";
    public static final String PAGERDUTY = "PAGERDUTY";
    public static final String CLOUDWATCH_SNS = "CLOUDWATCH_SNS";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Alert fromAlertmanager(JsonNode root) {
        JsonNode alerts = root == null ? null : root.get("alerts");
        if (alerts == null || !alerts.isArray() || alerts.isEmpty()) {
            throw new WebhookException(400, "Alertmanager payload must contain a non-empty 'alerts' array.");
        }
        JsonNode alert = alerts.get(0);
        JsonNode labels = alert.get("labels");
        JsonNode annotations = alert.get("annotations");
        String service = labels == null ? "" : labels.path("service").asText();
        String alertName = labels == null ? "" : labels.path("alertname").asText();
        String summary = annotations == null ? "" : annotations.path("summary").asText();
        String severity = labels == null ? "" : labels.path("severity").asText();
        return new Alert(ALERTMANAGER, service, summary, severityOrDefault(severity),
                fingerprint(ALERTMANAGER, alertName, service));
    }

    public static Alert fromPagerDuty(JsonNode root) {
        if (root == null || root.path("incident_key").asText().isBlank()) {
            throw new WebhookException(400, "PagerDuty payload must contain 'incident_key'.");
        }
        return new Alert(
                PAGERDUTY,
                root.path("service_name").asText(),
                root.path("title").asText(),
                severityOrDefault(root.path("severity").asText()),
                fingerprint(PAGERDUTY, root.path("incident_key").asText()));
    }

    public static Alert fromCloudWatchSns(JsonNode root) {
        if (root == null || root.path("Message").asText().isBlank()) {
            throw new WebhookException(400, "CloudWatch SNS payload must contain a 'Message' field.");
        }
        JsonNode alarm;
        try {
            alarm = MAPPER.readTree(root.path("Message").asText());
        } catch (Exception e) {
            throw new WebhookException(400, "CloudWatch SNS payload 'Message' is not valid JSON.");
        }
        String alarmName = alarm.path("AlarmName").asText();
        if (alarmName.isBlank()) {
            throw new WebhookException(400, "CloudWatch SNS payload must name the alarm in 'Message.AlarmName'.");
        }
        return new Alert(
                CLOUDWATCH_SNS,
                alarmName,
                alarm.path("StateReason").asText(),
                severityOrDefault(alarm.path("NewState").asText()),
                fingerprint(CLOUDWATCH_SNS, alarmName));
    }

    private static String severityOrDefault(String severity) {
        return severity.isBlank() ? "warning" : severity;
    }

    private static String fingerprint(String source, String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((source + "|" + String.join("|", parts)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
