package io.ajentic.sre.agent.masking;

import java.util.regex.Pattern;

/**
 * Every category of sensitive value the agent redacts before it reaches a model. Each constant owns the pattern that
 * finds it and the prefix of the placeholder written in its place, so masking rules can never drift from their names.
 */
public enum SensitiveDataCategory {

    ARN(Pattern.compile("arn:aws[a-zA-Z-]*:[a-z0-9-]+:[a-z0-9-]*:\\d{12}:[^\\s\"']+"), "arn"),
    EMAIL(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "email"),
    NAMESPACE(Pattern.compile("(?i)\\b(?:namespace|cluster|project|account)[-_][a-z0-9]{4,}\\b"), "namespace"),
    POD(Pattern.compile("\\b[a-z][a-z0-9-]{2,}-[0-9a-f]{6,10}-[a-z0-9]{4,5}\\b"), "pod"),
    IP(Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "ip"),
    ACCOUNT(Pattern.compile("\\b\\d{12}\\b"), "account"),
    API_KEY(Pattern.compile("\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b"), "api_key");

    private final Pattern pattern;
    private final String placeholderPrefix;

    SensitiveDataCategory(Pattern pattern, String placeholderPrefix) {
        this.pattern = pattern;
        this.placeholderPrefix = placeholderPrefix;
    }

    public Pattern pattern() {
        return pattern;
    }

    public String placeholderPrefix() {
        return placeholderPrefix;
    }
}
