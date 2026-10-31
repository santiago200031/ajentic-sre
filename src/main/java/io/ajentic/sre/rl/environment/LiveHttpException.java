package io.ajentic.sre.rl.environment;

/**
 * Thrown when a live upstream call fails (network error, 4xx/5xx response, or an open circuit).
 * Surfaces as a tool error, never as a crash.
 */
public class LiveHttpException extends RuntimeException {

    public LiveHttpException(String message) {
        super(message);
    }
}
