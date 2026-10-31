package io.ajentic.sre.agent.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resiliency components for outbound chat webhooks:
 * Circuit Breaker, Token-Bucket Rate Limiter, and exponential backoff retry with jitter.
 */
public final class ChatResilience {

    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public static class CircuitBreaker {
        private final int threshold;
        private final Duration cooldown;
        private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private volatile Instant lastOpenedAt = Instant.MIN;

        public CircuitBreaker(int threshold, Duration cooldown) {
            this.threshold = threshold;
            this.cooldown = cooldown;
        }

        public boolean allowExecution() {
            CircuitState current = state.get();
            if (current == CircuitState.CLOSED) {
                return true;
            }
            if (current == CircuitState.OPEN) {
                if (Duration.between(lastOpenedAt, Instant.now()).compareTo(cooldown) >= 0) {
                    if (state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                        return true;
                    }
                }
                return false;
            }
            // HALF_OPEN: allow trial request
            return true;
        }

        public void recordSuccess() {
            failureCount.set(0);
            state.set(CircuitState.CLOSED);
        }

        public void recordFailure() {
            int failures = failureCount.incrementAndGet();
            if (state.get() == CircuitState.HALF_OPEN || failures >= threshold) {
                state.set(CircuitState.OPEN);
                lastOpenedAt = Instant.now();
            }
        }

        public CircuitState state() {
            return state.get();
        }

        public void reset() {
            failureCount.set(0);
            state.set(CircuitState.CLOSED);
        }
    }

    public static class TokenBucketRateLimiter {
        private final double capacity;
        private final double refillRatePerSecond;
        private double tokens;
        private long lastRefillNanos;
        private final Object lock = new Object();

        public TokenBucketRateLimiter(double capacity, double refillRatePerSecond) {
            this.capacity = capacity;
            this.refillRatePerSecond = refillRatePerSecond;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        public boolean tryAcquire() {
            synchronized (lock) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return true;
                }
                return false;
            }
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                tokens = Math.min(capacity, tokens + elapsedSeconds * refillRatePerSecond);
                lastRefillNanos = now;
            }
        }
    }

    public static class RetryPolicy {
        private final int maxAttempts;
        private final Duration initialBackoff;

        public RetryPolicy(int maxAttempts, Duration initialBackoff) {
            this.maxAttempts = maxAttempts;
            this.initialBackoff = initialBackoff;
        }

        public int maxAttempts() {
            return maxAttempts;
        }

        public void backoff(int attempt) {
            if (attempt <= 0) {
                return;
            }
            long baseMs = initialBackoff.toMillis() * (1L << (attempt - 1));
            // Jitter +/- 20%
            double jitter = 0.8 + (ThreadLocalRandom.current().nextDouble() * 0.4);
            long sleepMs = Math.max(1, (long) (baseMs * jitter));
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private ChatResilience() {
    }
}
