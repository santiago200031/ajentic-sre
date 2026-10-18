package io.ajentic.sre.rl.episode;

public class UnknownEpisodeException extends RuntimeException {

    public UnknownEpisodeException(String id) {
        super("Unknown episode: " + id);
    }
}
