package io.ajentic.sre.agent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outbound Slack channel supporting incoming webhooks or chat.postMessage API.
 */
public class SlackChannel extends AbstractHttpNotificationChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_SLACK_API_URL = "https://slack.com/api";

    private final String webhookUrl;
    private final String token;
    private final String channel;
    private final String apiUrl;

    public SlackChannel(
            String webhookUrl,
            String token,
            String channel,
            String apiUrl,
            Duration timeout,
            int maxRetries,
            Duration retryBackoff,
            int circuitThreshold,
            Duration circuitCooldown,
            double rateLimitPerSecond) {
        super(timeout, maxRetries, retryBackoff, circuitThreshold, circuitCooldown, rateLimitPerSecond);
        this.webhookUrl = webhookUrl != null ? webhookUrl.trim() : null;
        this.token = token != null ? token.trim() : null;
        this.channel = channel != null ? channel.trim() : null;
        this.apiUrl = (apiUrl != null && !apiUrl.isBlank()) ? apiUrl.trim() : DEFAULT_SLACK_API_URL;
    }

    public SlackChannel(
            HttpClient httpClient,
            String webhookUrl,
            String token,
            String channel,
            String apiUrl,
            Duration timeout,
            ChatResilience.CircuitBreaker circuitBreaker,
            ChatResilience.TokenBucketRateLimiter rateLimiter,
            ChatResilience.RetryPolicy retryPolicy) {
        super(httpClient, timeout, circuitBreaker, rateLimiter, retryPolicy);
        this.webhookUrl = webhookUrl != null ? webhookUrl.trim() : null;
        this.token = token != null ? token.trim() : null;
        this.channel = channel != null ? channel.trim() : null;
        this.apiUrl = (apiUrl != null && !apiUrl.isBlank()) ? apiUrl.trim() : DEFAULT_SLACK_API_URL;
    }

    @Override
    public String name() {
        return "slack";
    }

    @Override
    public boolean isConfigured() {
        return (webhookUrl != null && !webhookUrl.isBlank()) || (token != null && !token.isBlank());
    }

    @Override
    protected HttpRequest createRequest(IncidentSummary summary) throws Exception {
        String formatted = ChatFormat.SLACK_MRKDWN.format(summary);

        if (webhookUrl != null && !webhookUrl.isBlank()) {
            Map<String, Object> payload = Map.of(
                    "text", formatted,
                    "mrkdwn", true
            );
            return HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .build();
        } else {
            URI endpoint = URI.create(apiUrl.endsWith("/") ? apiUrl + "chat.postMessage" : apiUrl + "/chat.postMessage");
            Map<String, Object> payload = new LinkedHashMap<>();
            if (channel != null && !channel.isBlank()) {
                payload.put("channel", channel);
            }
            payload.put("text", formatted);
            payload.put("mrkdwn", true);

            return HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .build();
        }
    }
}
