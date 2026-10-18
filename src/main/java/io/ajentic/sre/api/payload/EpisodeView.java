package io.ajentic.sre.api.payload;

import io.ajentic.sre.rl.episode.EpisodeHandle;
import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.rl.Observation;

public record EpisodeView(String id, Observation observation, boolean completed, EpisodeResult result) {

    public static EpisodeView from(EpisodeHandle handle) {
        return new EpisodeView(
                handle.id(),
                handle.environment().observe(),
                handle.result().isPresent(),
                handle.result().orElse(null));
    }
}
