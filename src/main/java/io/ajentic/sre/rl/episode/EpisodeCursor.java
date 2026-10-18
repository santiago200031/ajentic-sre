package io.ajentic.sre.rl.episode;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the episode a tool call targets when the caller does not name one explicitly. Shared by the MCP transport
 * and the lifecycle tools so both agree on the active episode.
 */
@ApplicationScoped
public class EpisodeCursor {

    private final AtomicReference<String> active = new AtomicReference<>();

    public String activeId() {
        return active.get();
    }

    public void activate(String episodeId) {
        active.set(episodeId);
    }

    public Optional<String> clear() {
        return Optional.ofNullable(active.getAndSet(null));
    }
}
