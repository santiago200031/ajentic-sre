package io.ajentic.sre.rl.episode;

import io.ajentic.sre.rl.Environment;

import java.util.Optional;

public final class EpisodeHandle {

    private final String id;
    private final Environment environment;

    private volatile EpisodeResult result;

    public EpisodeHandle(String id, Environment environment) {
        this.id = id;
        this.environment = environment;
    }

    public String id() {
        return id;
    }

    public Environment environment() {
        return environment;
    }

    public Optional<EpisodeResult> result() {
        return Optional.ofNullable(result);
    }

    public void complete(EpisodeResult episodeResult) {
        this.result = episodeResult;
    }
}
