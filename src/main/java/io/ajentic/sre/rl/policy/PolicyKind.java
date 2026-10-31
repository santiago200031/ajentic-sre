package io.ajentic.sre.rl.policy;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;
import java.util.Locale;

/**
 * The policy implementations an episode can be run with. Keeps the name-to-policy dispatch in one place instead of a
 * stringly-typed switch in the factory.
 */
public enum PolicyKind {

    HEURISTIC,
    IDLE,
    LLM,
    RANDOM;

    @JsonCreator
    public static PolicyKind fromName(String name) {
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
