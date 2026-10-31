package io.ajentic.sre.agent.session;

/**
 * Receives {@link TurnEvent}s as they are produced during one {@code chat} turn. The default implementation
 * discards events, keeping the non-streaming path unchanged.
 */
@FunctionalInterface
public interface TurnListener {

    TurnListener NOOP = event -> { };

    void onEvent(TurnEvent event);
}
