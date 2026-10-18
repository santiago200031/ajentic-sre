package io.ajentic.sre.rl.episode;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.policy.Policy;
import io.ajentic.sre.rl.simulation.EnvironmentFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tracks the active episodes. The active set is bounded with a Caffeine cache so a hosted deployment cannot grow
 * without limit; once the cap is reached the cache evicts entries to stay within it.
 */
@ApplicationScoped
public class EpisodeService {

    private static final int DEFAULT_MAX_ACTIVE = 1024;

    private final EnvironmentFactory environments;
    private final EpisodeRunner runner;
    private final Map<String, EpisodeHandle> episodes;

    @Inject
    public EpisodeService(EnvironmentFactory environments, EpisodeRunner runner) {
        this.environments = environments;
        this.runner = runner;
        this.episodes = Caffeine.newBuilder()
                .maximumSize(DEFAULT_MAX_ACTIVE)
                .<String, EpisodeHandle>build()
                .asMap();
    }

    public EpisodeHandle start(String scenarioId, long seed) {
        Environment environment = environments.create(scenarioId, seed);
        EpisodeHandle handle = new EpisodeHandle(UUID.randomUUID().toString(), environment);
        episodes.put(handle.id(), handle);
        return handle;
    }

    public Optional<EpisodeHandle> find(String id) {
        return Optional.ofNullable(episodes.get(id));
    }

    public EpisodeResult run(String id, Policy policy) {
        EpisodeHandle handle = find(id).orElseThrow(() -> new UnknownEpisodeException(id));
        EpisodeResult result = runner.run(id, handle.environment(), policy);
        handle.complete(result);
        return result;
    }

    public void delete(String id) {
        episodes.remove(id);
    }
}
