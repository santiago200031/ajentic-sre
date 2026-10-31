package io.ajentic.sre.agent.cloud.azure;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages OAuth2 Bearer tokens for Azure REST APIs with client credentials and IMDS fallback.
 * Caches tokens until expiry with clock-skew leeway and thread-safe refresh.
 */
public final class AzureTokenSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration SKEW_LEEWAY = Duration.ofSeconds(60);

    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final String tokenUrl;
    private final String imdsUrl;
    private final HttpClient httpClient;
    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    public AzureTokenSource(
            String tenantId,
            String clientId,
            String clientSecret,
            String scope,
            String tokenUrlOverride,
            String imdsUrlOverride,
            HttpClient httpClient) {
        this.tenantId = tenantId != null ? tenantId.trim() : "";
        this.clientId = clientId != null ? clientId.trim() : "";
        this.clientSecret = clientSecret != null ? clientSecret.trim() : "";
        this.scope = (scope != null && !scope.isBlank()) ? scope.trim() : "https://management.azure.com/.default";
        this.tokenUrl = (tokenUrlOverride != null && !tokenUrlOverride.isBlank())
                ? tokenUrlOverride
                : "https://login.microsoftonline.com/" + (this.tenantId.isEmpty() ? "common" : this.tenantId) + "/oauth2/v2.0/token";
        this.imdsUrl = (imdsUrlOverride != null && !imdsUrlOverride.isBlank())
                ? imdsUrlOverride
                : "http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource="
                + URLEncoder.encode("https://management.azure.com", StandardCharsets.UTF_8);
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public synchronized String getAccessToken() {
        CachedToken current = cachedToken.get();
        Instant now = Instant.now();
        if (current != null && current.expiresAt().isAfter(now.plus(SKEW_LEEWAY))) {
            return current.token();
        }

        CachedToken fresh = fetchToken();
        cachedToken.set(fresh);
        return fresh.token();
    }

    public void invalidate() {
        cachedToken.set(null);
    }

    private CachedToken fetchToken() {
        if (!clientId.isEmpty() && !clientSecret.isEmpty()) {
            return fetchViaClientCredentials();
        } else {
            return fetchViaImds();
        }
    }

    private CachedToken fetchViaClientCredentials() {
        try {
            String form = "grant_type=client_credentials"
                    + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                    + "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Azure token request failed (" + response.statusCode() + "): " + response.body());
            }

            JsonNode json = MAPPER.readTree(response.body());
            String token = json.path("access_token").asString();
            long expiresInSeconds = json.path("expires_in").asLong(3600);
            Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds);
            return new CachedToken(token, expiresAt);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to obtain Azure OAuth2 token via client credentials", e);
        }
    }

    private CachedToken fetchViaImds() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(imdsUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Metadata", "true")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Azure IMDS token request failed (" + response.statusCode() + "): " + response.body());
            }

            JsonNode json = MAPPER.readTree(response.body());
            String token = json.path("access_token").asString();
            long expiresInSeconds = json.path("expires_in").asLong(3600);
            Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds);
            return new CachedToken(token, expiresAt);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to obtain Azure token via IMDS", e);
        }
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}
