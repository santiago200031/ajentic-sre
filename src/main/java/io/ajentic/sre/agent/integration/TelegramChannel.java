package io.ajentic.sre.agent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

/**
 * Outbound Telegram channel using Telegram Bot API sendMessage.
 */
public class TelegramChannel extends AbstractHttpNotificationChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_API_URL = "https://api.telegram.org";

    private final String botToken;
    private final String chatId;
    private final String apiUrl;

    public TelegramChannel(
            String botToken,
            String chatId,
            String apiUrl,
            Duration timeout,
            int maxRetries,
            Duration retryBackoff,
            int circuitThreshold,
            Duration circuitCooldown,
            double rateLimitPerSecond) {
        super(timeout, maxRetries, retryBackoff, circuitThreshold, circuitCooldown, rateLimitPerSecond);
        this.botToken = botToken != null ? botToken.trim() : null;
        this.chatId = chatId != null ? chatId.trim() : null;
        this.apiUrl = (apiUrl != null && !apiUrl.isBlank()) ? apiUrl.trim() : DEFAULT_API_URL;
    }

    public TelegramChannel(
            HttpClient httpClient,
            String botToken,
            String chatId,
            String apiUrl,
            Duration timeout,
            ChatResilience.CircuitBreaker circuitBreaker,
            ChatResilience.TokenBucketRateLimiter rateLimiter,
            ChatResilience.RetryPolicy retryPolicy) {
        super(httpClient, timeout, circuitBreaker, rateLimiter, retryPolicy);
        this.botToken = botToken != null ? botToken.trim() : null;
        this.chatId = chatId != null ? chatId.trim() : null;
        this.apiUrl = (apiUrl != null && !apiUrl.isBlank()) ? apiUrl.trim() : DEFAULT_API_URL;
    }

    @Override
    public String name() {
        return "telegram";
    }

    @Override
    public boolean isConfigured() {
        return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
    }

    @Override
    protected HttpRequest createRequest(IncidentSummary summary) throws Exception {
        String formatted = ChatFormat.TELEGRAM.format(summary);
        String token = botToken.startsWith("bot") ? botToken.substring(3) : botToken;
        String url = (apiUrl.endsWith("/") ? apiUrl : apiUrl + "/") + "bot" + token + "/sendMessage";

        Map<String, Object> payload = Map.of(
                "chat_id", chatId,
                "text", formatted,
                "parse_mode", "Markdown"
        );

        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();
    }
}
