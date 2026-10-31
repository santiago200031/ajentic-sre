package io.ajentic.sre.agent.integration;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

/**
 * Outbound Google Chat channel posting to chat.googleapis.com incoming webhook.
 */
public class GoogleChatChannel extends AbstractHttpNotificationChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String webhookUrl;

    public GoogleChatChannel(
            String webhookUrl,
            Duration timeout,
            int maxRetries,
            Duration retryBackoff,
            int circuitThreshold,
            Duration circuitCooldown,
            double rateLimitPerSecond) {
        super(timeout, maxRetries, retryBackoff, circuitThreshold, circuitCooldown, rateLimitPerSecond);
        this.webhookUrl = webhookUrl != null ? webhookUrl.trim() : null;
    }

    public GoogleChatChannel(
            HttpClient httpClient,
            String webhookUrl,
            Duration timeout,
            ChatResilience.CircuitBreaker circuitBreaker,
            ChatResilience.TokenBucketRateLimiter rateLimiter,
            ChatResilience.RetryPolicy retryPolicy) {
        super(httpClient, timeout, circuitBreaker, rateLimiter, retryPolicy);
        this.webhookUrl = webhookUrl != null ? webhookUrl.trim() : null;
    }

    @Override
    public String name() {
        return "google-chat";
    }

    @Override
    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    @Override
    protected HttpRequest createRequest(IncidentSummary summary) throws Exception {
        String formatted = ChatFormat.GOOGLE_CHAT.format(summary);
        Map<String, Object> payload = Map.of("text", formatted);

        return HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();
    }
}
