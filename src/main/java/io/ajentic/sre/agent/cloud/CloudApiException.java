package io.ajentic.sre.agent.cloud;

/**
 * Thrown when a cloud provider API call fails or exceeds limits.
 */
public class CloudApiException extends RuntimeException {

    private final int statusCode;

    public CloudApiException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public CloudApiException(int statusCode, String message) {
        super("HTTP " + statusCode + ": " + message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
