package io.ajentic.sre.rl.action;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.ajentic.sre.agent.tool.Capability;

import java.util.Arrays;
import java.util.Locale;

/**
 * Canonical identity of every agent action. The {@link #wireName()} matches both the REST {@code type} field and the
 * built-in tool name, and {@link #capability()} tells the tool layer whether the action may run during the parallel
 * read-only phase.
 */
public enum ActionKind {

    QUERY_TELEMETRY(Capability.READ),
    READ_RUNBOOK(Capability.READ),
    LIST_REMEDIATIONS(Capability.READ),
    APPLY_REMEDIATION(Capability.MUTATE),
    DECLARE_DIAGNOSIS(Capability.MUTATE),
    IDLE(Capability.READ);

    private final Capability capability;

    ActionKind(Capability capability) {
        this.capability = capability;
    }

    public Capability capability() {
        return capability;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ActionKind fromWireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Action 'type' is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(kind -> kind.wireName().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown action type: " + value));
    }
}
