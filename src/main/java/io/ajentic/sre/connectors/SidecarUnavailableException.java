package io.ajentic.sre.connectors;

public class SidecarUnavailableException extends RuntimeException {
    public SidecarUnavailableException(String message) {
        super(message);
    }

    public SidecarUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
