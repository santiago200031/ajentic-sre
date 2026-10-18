package io.ajentic.sre.agent.session;

import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;

/**
 * Tracks the active agent sessions. The active set is bounded with a Caffeine cache so a hosted deployment cannot
 * grow without limit; once the cap is reached the cache evicts entries to stay within it.
 */
@ApplicationScoped
public class SessionService {

    private static final int DEFAULT_MAX_ACTIVE = 1024;

    private final AgentSessionFactory sessions;
    private final Map<String, AgentSession> active;

    @Inject
    public SessionService(AgentSessionFactory sessions) {
        this.sessions = sessions;
        this.active = Caffeine.newBuilder()
                .maximumSize(DEFAULT_MAX_ACTIVE)
                .<String, AgentSession>build()
                .asMap();
    }

    public AgentSession create(AgentSessionConfig config) {
        AgentSession session = sessions.create(config);
        active.put(session.id(), session);
        return session;
    }

    public Optional<AgentSession> find(String id) {
        return Optional.ofNullable(active.get(id));
    }

    public void delete(String id) {
        active.remove(id);
    }
}
