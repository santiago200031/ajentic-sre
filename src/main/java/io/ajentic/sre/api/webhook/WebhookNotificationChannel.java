package io.ajentic.sre.api.webhook;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Posts the report as JSON to a webhook URL (Slack incoming webhooks and similar accept this shape). Failures are
 * logged, never thrown: notification must not fail the intake.
 */
public class WebhookNotificationChannel implements NotificationChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final String url;
    private final HttpClient http;

    public WebhookNotificationChannel(String url) {
        this.url = url;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public void notify(AlertReport report) {
        try {
            Alert alert = report.alert();
            String body = MAPPER.writeValueAsString(Map.of(
                    "fingerprint", alert.fingerprint(),
                    "source", alert.source(),
                    "service", alert.service(),
                    "summary", alert.summary(),
                    "severity", alert.severity(),
                    "scenarioId", report.scenarioId(),
                    "sessionId", report.sessionId(),
                    "report", report.answer()));
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(TIMEOUT)
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                System.err.println("Notification webhook " + url + " returned " + response.statusCode());
            }
        } catch (Exception e) {
            System.err.println("Notification webhook " + url + " failed: " + e.getMessage());
        }
    }
}
