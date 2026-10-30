package io.ajentic.sre.rl.episode;

import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;

/**
 * Tracks the episode a tool call targets when the caller does not name one explicitly. Scoped per MCP session or
 * agent session so concurrent clients cannot overwrite each other's active episode.
 */
@ApplicationScoped
public class EpisodeCursor {

    public static final String DEFAULT_SCOPE = "stdio";

    private final Map<String, String> active = Caffeine.newBuilder()
            .maximumSize(1024)
            .<String, String>build()
            .asMap();

    public String activeId(String scope) {
        return active.get(scope == null ? DEFAULT_SCOPE : scope);
    }

    public void activate(String scope, String episodeId) {
        active.put(scope == null ? DEFAULT_SCOPE : scope, episodeId);
    }

    public Optional<String> clear(String scope) {
        return Optional.ofNullable(active.remove(scope == null ? DEFAULT_SCOPE : scope));
    }

    public void clearAll() {
        active.clear();
    }
}
