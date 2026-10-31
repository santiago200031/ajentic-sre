package io.ajentic.sre.agent.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * Outbound Generic Webhook channel posting signed JSON to a webhook URL.
 */
public class GenericWebhookChannel extends AbstractHttpNotificationChannel {

    private final String url;
    private final String secret;

    public GenericWebhookChannel(
            String url,
            String secret,
            Duration timeout,
            int maxRetries,
            Duration retryBackoff,
            int circuitThreshold,
            Duration circuitCooldown,
            double rateLimitPerSecond) {
        super(timeout, maxRetries, retryBackoff, circuitThreshold, circuitCooldown, rateLimitPerSecond);
        this.url = url != null ? url.trim() : null;
        this.secret = secret != null ? secret.trim() : null;
    }

    public GenericWebhookChannel(
            HttpClient httpClient,
            String url,
            String secret,
            Duration timeout,
            ChatResilience.CircuitBreaker circuitBreaker,
            ChatResilience.TokenBucketRateLimiter rateLimiter,
            ChatResilience.RetryPolicy retryPolicy) {
        super(httpClient, timeout, circuitBreaker, rateLimiter, retryPolicy);
        this.url = url != null ? url.trim() : null;
        this.secret = secret != null ? secret.trim() : null;
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }

    @Override
    protected HttpRequest createRequest(IncidentSummary summary) throws Exception {
        String jsonBody = ChatFormat.GENERIC_JSON.format(summary);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (secret != null && !secret.isBlank()) {
            String signature = ChatCrypto.hmacSha256Hex(jsonBody, secret);
            builder.header("X-Ajentic-Signature", "sha256=" + signature);
        }

        return builder.build();
    }
}
