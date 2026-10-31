package io.ajentic.sre.simulation.environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin JDK {@link HttpClient} wrapper with bounded retries and a circuit breaker so a misbehaving
 * upstream can never block a virtual thread indefinitely. 5xx and network errors are retried once;
 * 4xx fails immediately; all failures surface as {@link LiveHttpException}.
 */
public final class LiveHttp {

    private final HttpClient client;
    private final CircuitBreaker breaker;
    private final int maxAttempts;

    public LiveHttp(Duration timeout, int maxAttempts) {
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.breaker = new CircuitBreaker(3, Duration.ofSeconds(10));
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public String get(String url) {
        return send(url, null);
    }

    public String post(String url, String json) {
        return send(url, json);
    }

    private String send(String url, String json) {
        breaker.checkClosed();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
        if (json == null) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json");
        }
        LiveHttpException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new LiveHttpException("upstream " + response.statusCode() + " from " + url);
                }
                breaker.recordSuccess();
                return response.body();
            } catch (LiveHttpException e) {
                breaker.recordFailure();
                last = e;
                // 4xx will not heal by retrying; only 5xx/network errors earn a second attempt.
                if (e.getMessage().startsWith("upstream 4")) {
                    throw e;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LiveHttpException("interrupted while calling " + url);
            } catch (java.io.IOException e) {
                breaker.recordFailure();
                last = new LiveHttpException("request to " + url + " failed: " + e);
            }
            if (attempt < maxAttempts) {
                continue;
            }
            throw last;
        }
        throw last;
    }
}
