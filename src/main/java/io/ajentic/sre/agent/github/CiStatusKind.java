package io.ajentic.sre.agent.github;

/**
 * Normalized status of a continuous integration (CI) run.
 */
public enum CiStatusKind {
    PENDING("PENDING"),
    SUCCESS("SUCCESS"),
    FAILURE("FAILURE"),
    TIMED_OUT("TIMED_OUT");

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

    /**
     * Standard GitHub check run statuses and conclusions.
     */
    public static final class GitHubCheckRuns {
        public static final String STATUS_COMPLETED = "completed";
        public static final String CONCLUSION_FAILURE = "failure";
        public static final String CONCLUSION_TIMED_OUT = "timed_out";
        public static final String CONCLUSION_CANCELLED = "cancelled";
        public static final String CONCLUSION_SUCCESS = "success";
        public static final String CONCLUSION_NEUTRAL = "neutral";
        public static final String CONCLUSION_SKIPPED = "skipped";

        private GitHubCheckRuns() {
        }
    }
}

