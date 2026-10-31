package io.ajentic.sre.agent.github;

/**
 * Normalized status of a continuous integration (CI) run.
 */
public enum CiStatusKind {
    PENDING("PENDING"),
    SUCCESS("SUCCESS"),
    FAILURE("FAILURE");

    private final String value;

    CiStatusKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static CiStatusKind fromString(String text) {
        if (text == null) {
            return PENDING;
        }
        for (CiStatusKind kind : values()) {
            if (kind.value.equalsIgnoreCase(text.trim())) {
                return kind;
            }
        }
        return PENDING;
    }

    public boolean isTerminal() {
        return this != PENDING;
    }
}
