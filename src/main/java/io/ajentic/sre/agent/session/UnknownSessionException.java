package io.ajentic.sre.agent.session;

public class UnknownSessionException extends RuntimeException {

    public UnknownSessionException(String id) {
        super("Unknown agent session: " + id);
    }
}
