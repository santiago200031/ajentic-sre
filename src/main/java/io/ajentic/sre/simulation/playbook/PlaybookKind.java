package io.ajentic.sre.simulation.playbook;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;
import java.util.Locale;

/**
 * The policy implementations an episode can be run with. Keeps the name-to-policy dispatch in one place instead of a
 * stringly-typed switch in the factory.
 */
public enum PlaybookKind {

    HEURISTIC,
    IDLE,
    LLM,
    RANDOM;

    @JsonCreator
    public static PlaybookKind fromName(String name) {
        if (name == null || name.isBlank()) {
            return HEURISTIC;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(kind -> kind.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown policy: " + name));
    }
}
