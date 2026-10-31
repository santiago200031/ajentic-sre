package io.ajentic.sre.rl.environment;

import java.time.Duration;

/**
 * Minimal circuit breaker: after {@code failureThreshold} consecutive failures it opens for
 * {@code openDuration} and fails fast until it half-opens. Thread-safe.
 */
public final class CircuitBreaker {

    private final int failureThreshold;
    private final Duration openDuration;

    private int consecutiveFailures;
    private long openedAtNanos;

    public CircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    /** Fails fast while the breaker is open. */
    public synchronized void checkClosed() {
        if (consecutiveFailures >= failureThreshold && System.nanoTime() - openedAtNanos < openDuration.toNanos()) {
            throw new LiveHttpException("circuit breaker open after " + consecutiveFailures + " consecutive failures");
        }
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
    }

    public synchronized void recordFailure() {
        if (consecutiveFailures >= failureThreshold) {
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures == failureThreshold) {
            openedAtNanos = System.nanoTime();
        }
    }
}
