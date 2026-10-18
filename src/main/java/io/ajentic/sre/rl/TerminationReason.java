package io.ajentic.sre.rl;

public enum TerminationReason {
    NONE,
    RESOLVED,
    MAX_STEPS,
    CATASTROPHIC
}
