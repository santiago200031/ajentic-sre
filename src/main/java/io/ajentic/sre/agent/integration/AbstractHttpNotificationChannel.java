package io.ajentic.sre.agent.integration;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Common base class for outbound HTTP notification channels.
 * Encapsulates circuit breaker, token-bucket rate limiting, timeout, and bounded retry with jitter.
 */
public abstract class AbstractHttpNotificationChannel implements NotificationChannel {

    private final Logger log = Logger.getLogger(getClass());

    protected final HttpClient httpClient;
    protected final Duration timeout;
    protected final ChatResilience.CircuitBreaker circuitBreaker;
    protected final ChatResilience.TokenBucketRateLimiter rateLimiter;
    protected final ChatResilience.RetryPolicy retryPolicy;

    protected AbstractHttpNotificationChannel(
            Duration timeout,
            int maxRetries,
            Duration retryBackoff,
            int circuitThreshold,
            Duration circuitCooldown,
            double rateLimitPerSecond) {
        this(
                HttpClient.newBuilder().connectTimeout(timeout).build(),
                timeout,
                new ChatResilience.CircuitBreaker(circuitThreshold, circuitCooldown),
                new ChatResilience.TokenBucketRateLimiter(rateLimitPerSecond * 2, rateLimitPerSecond),
                new ChatResilience.RetryPolicy(maxRetries, retryBackoff)
        );
    }

    protected AbstractHttpNotificationChannel(
            HttpClient httpClient,
            Duration timeout,
            ChatResilience.CircuitBreaker circuitBreaker,
            ChatResilience.TokenBucketRateLimiter rateLimiter,
            ChatResilience.RetryPolicy retryPolicy) {
        this.httpClient = httpClient;
        this.timeout = timeout;
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
        this.retryPolicy = retryPolicy;
    }

    /**
     * Checks if this channel has sufficient configuration (URL, tokens, etc.) to post.
     */
    public abstract boolean isConfigured();

    /**
     * Builds the HttpRequest to be sent for this summary.
     */
    protected abstract HttpRequest createRequest(IncidentSummary summary) throws Exception;

    @Override
    public void post(IncidentSummary summary) {
        if (!isConfigured()) {
            log.debugf("Channel %s is disabled (blank configuration)", name());
            return;
        }

        if (!circuitBreaker.allowExecution()) {
            log.warnf("Channel %s circuit breaker is OPEN; dropping notification", name());
            return;
        }

        if (!rateLimiter.tryAcquire()) {
            log.warnf("Channel %s rate limit exceeded; dropping notification", name());
            return;
        }

        int attempts = 0;
        while (attempts < retryPolicy.maxAttempts()) {
            attempts++;
            try {
                HttpRequest request = createRequest(summary);
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    circuitBreaker.recordSuccess();
                    log.debugf("Channel %s successfully delivered notification (status %d)", name(), status);
                    return;
                }

                if (status == 429 || status >= 500) {
                    log.warnf("Channel %s transient failure status %d on attempt %d/%d: %s",
                            name(), status, attempts, retryPolicy.maxAttempts(), response.body());
                    if (attempts < retryPolicy.maxAttempts()) {
                        retryPolicy.backoff(attempts);
                        continue;
                    }
                } else {
                    log.errorf("Channel %s non-retryable client error status %d: %s", name(), status, response.body());
                    return;
                }
            } catch (IOException e) {
                log.warnf("Channel %s network error on attempt %d/%d: %s",
                        name(), attempts, retryPolicy.maxAttempts(), e.getMessage());
                if (attempts < retryPolicy.maxAttempts()) {
                    retryPolicy.backoff(attempts);
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warnf("Channel %s send interrupted", name());
                return;
            } catch (Exception e) {
                log.errorf("Channel %s unexpected failure creating/sending request: %s", name(), e.getMessage());
                return;
            }
        }

        // All retries failed
        circuitBreaker.recordFailure();
        log.warnf("Channel %s failed to post after %d attempts", name(), attempts);
    }

    public ChatResilience.CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    public ChatResilience.TokenBucketRateLimiter rateLimiter() {
        return rateLimiter;
    }
}
