package io.ajentic.sre.agent.cloud;

import io.ajentic.sre.rl.environment.CircuitBreaker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resilient HTTP client for Cloud Provider REST APIs.
 * Enforces connect and request timeouts, per-turn call budgets, circuit breakers,
 * and retries on 429/5xx honouring Retry-After headers.
 */
public final class CloudHttpClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final int DEFAULT_PER_TURN_BUDGET = 25;

    private final HttpClient client;
    private final Duration requestTimeout;
    private final CircuitBreaker circuitBreaker;
    private final int maxRetries;
    private final int callBudget;
    private final AtomicInteger callsThisTurn = new AtomicInteger(0);

    public CloudHttpClient() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, DEFAULT_MAX_RETRIES, DEFAULT_PER_TURN_BUDGET);
    }

    public CloudHttpClient(Duration connectTimeout, Duration requestTimeout, int maxRetries, int callBudget) {
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.requestTimeout = requestTimeout;
        this.circuitBreaker = new CircuitBreaker(3, Duration.ofSeconds(10));
        this.maxRetries = maxRetries;
        this.callBudget = callBudget;
    }

    public void resetTurnBudget() {
        callsThisTurn.set(0);
    }

    public int callsThisTurn() {
        return callsThisTurn.get();
    }

    public HttpResponse<String> send(HttpRequest.Builder requestBuilder) {
        if (callsThisTurn.incrementAndGet() > callBudget) {
            throw new CloudApiException("API call budget of " + callBudget + " calls exceeded for this turn");
        }

        circuitBreaker.checkClosed();

        requestBuilder.timeout(requestTimeout);

        CloudApiException lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = requestBuilder.build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    circuitBreaker.recordFailure();
                    lastException = new CloudApiException(status, response.body());
                    if (attempt < maxRetries) {
                        boolean waited = applyRetryAfter(response);
                        if (!waited) {
                            try {
                                Thread.sleep(50L * (attempt + 1));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new CloudApiException("Interrupted during retry backoff");
                            }
                        }
                        continue;
                    }
                    throw lastException;
                }

                if (status >= 400) {
                    circuitBreaker.recordFailure();
                    throw new CloudApiException(status, response.body());
                }

                circuitBreaker.recordSuccess();
                return response;
            } catch (CloudApiException e) {
                lastException = e;
                if (e.statusCode() < 500 && e.statusCode() != 429) {
                    throw e;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudApiException("Request interrupted: " + e.getMessage());
            } catch (Exception e) {
                circuitBreaker.recordFailure();
                lastException = new CloudApiException("Network error: " + e.getMessage());
            }

            if (attempt < maxRetries) {
                try {
                    Thread.sleep(50L * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CloudApiException("Interrupted during retry");
                }
            }
        }

        throw lastException != null ? lastException : new CloudApiException("Request failed after retries");
    }

    private boolean applyRetryAfter(HttpResponse<?> response) {
        java.util.Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            try {
                long seconds = Long.parseLong(header.get().trim());
                long sleepMs = Math.min(seconds * 1000L, 500L); // Cap sleep in tests/production to keep virtual thread responsive
                Thread.sleep(sleepMs);
                return true;
            } catch (NumberFormatException | InterruptedException ignored) {
                // fall back to loop backoff
            }
        }
        return false;
    }
}
