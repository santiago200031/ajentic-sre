package io.ajentic.sre.agent.tool;

import java.util.Locale;

/**
 * The three outcomes of the mutation gate. READ tools are always {@code ALLOW}ed; the policy only decides
 * mutating tools.
 */
public enum AuthorizationDecision {

    ALLOW,
    DENY,
    REQUIRE_APPROVAL;

    public static AuthorizationDecision fromName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Unknown mutation policy: " + value);
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "allow" -> ALLOW;
            case "deny" -> DENY;
            case "require-approval" -> REQUIRE_APPROVAL;
            default -> throw new IllegalArgumentException("Unknown mutation policy: " + value);
        };
    }
}
