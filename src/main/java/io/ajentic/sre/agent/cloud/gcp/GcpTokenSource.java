package io.ajentic.sre.agent.cloud.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages OAuth2 Bearer tokens for GCP APIs using Service Account JWT assertion (RS256)
 * or GCE metadata server fallback. Caches tokens with thread-safe refresh.
 */
public final class GcpTokenSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration SKEW_LEEWAY = Duration.ofSeconds(60);

    private final String clientEmail;
    private final PrivateKey privateKey;
    private final String scope;
    private final String tokenUrl;
    private final String metadataUrl;
    private final HttpClient httpClient;
    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    public GcpTokenSource(
            String clientEmail,
            String privateKeyPem,
            String scope,
            String tokenUrlOverride,
            String metadataUrlOverride,
            HttpClient httpClient) {
        this.clientEmail = clientEmail != null ? clientEmail.trim() : "";
        this.privateKey = parsePrivateKey(privateKeyPem);
        this.scope = (scope != null && !scope.isBlank()) ? scope.trim() : "https://www.googleapis.com/auth/cloud-platform";
        this.tokenUrl = (tokenUrlOverride != null && !tokenUrlOverride.isBlank())
                ? tokenUrlOverride
                : "https://oauth2.googleapis.com/token";
        this.metadataUrl = (metadataUrlOverride != null && !metadataUrlOverride.isBlank())
                ? metadataUrlOverride
                : "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token";
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
        if (!clientEmail.isEmpty() && privateKey != null) {
            return fetchViaJwtAssertion();
        } else {
            return fetchViaMetadata();
        }
    }

    private CachedToken fetchViaJwtAssertion() {
        try {
            long nowSec = Instant.now().getEpochSecond();
            long expSec = nowSec + 3600;

            String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
            String claimsJson = "{\"iss\":\"" + clientEmail + "\","
                    + "\"sub\":\"" + clientEmail + "\","
                    + "\"aud\":\"" + tokenUrl + "\","
                    + "\"iat\":" + nowSec + ","
                    + "\"exp\":" + expSec + ","
                    + "\"scope\":\"" + scope + "\"}";

            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String signingInput = encoder.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
                    + "." + encoder.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = sig.sign();
            String jwt = signingInput + "." + encoder.encodeToString(signatureBytes);

            String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                    + "&assertion=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("GCP token assertion failed (" + response.statusCode() + "): " + response.body());
            }

            JsonNode json = MAPPER.readTree(response.body());
            String token = json.path("access_token").asText();
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("GCP token response missing access_token: " + response.body());
            }
            long expiresIn = json.path("expires_in").asLong(3600);
            return new CachedToken(token, Instant.now().plusSeconds(expiresIn));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to obtain GCP OAuth2 token via JWT assertion", e);
        }
    }

    private CachedToken fetchViaMetadata() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(metadataUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Metadata-Flavor", "Google")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("GCP metadata token request failed (" + response.statusCode() + "): " + response.body());
            }

            JsonNode json = MAPPER.readTree(response.body());
            String token = json.path("access_token").asText();
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("GCP metadata token response missing access_token: " + response.body());
            }
            long expiresIn = json.path("expires_in").asLong(3600);
            return new CachedToken(token, Instant.now().plusSeconds(expiresIn));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to obtain GCP token via metadata server", e);
        }
    }

    private static PrivateKey parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        try {
            String sanitized = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] encoded = Base64.getDecoder().decode(sanitized);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid RSA private key PEM for GCP service account", e);
        }
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}
