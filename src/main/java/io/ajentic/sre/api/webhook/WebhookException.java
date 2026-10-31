package io.ajentic.sre.api.webhook;

/**
 * Thrown by the webhook intake path with the HTTP status it should surface (400, 401, 429).
 */
public class WebhookException extends RuntimeException {

    private final int status;

    public WebhookException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
