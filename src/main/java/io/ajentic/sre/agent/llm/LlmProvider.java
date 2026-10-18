package io.ajentic.sre.agent.llm;

import java.util.Arrays;
import java.util.Locale;

/**
 * The LLM backends {@code ajentic-sre} can talk to. Every HTTP provider speaks the same OpenAI
 * {@code /chat/completions} wire contract; they differ only in the endpoint shape and the header used to
 * authenticate. {@link #SCRIPTED} is the fully offline client used by the test suite.
 */
public enum LlmProvider {

    OPENAI(true),
    OPENAI_COMPATIBLE(true),
    AZURE(true),
    AWS(true),
    LLAMACPP(true),
    OLLAMA(true),
    VLLM(true),
    WIREMOCK(true),
    SCRIPTED(false);

    private final boolean httpTransport;

    LlmProvider(boolean httpTransport) {
        this.httpTransport = httpTransport;
    }

    public boolean usesHttpTransport() {
        return httpTransport;
    }

    /** Azure authenticates with its own {@code api-key} header; every other provider uses a bearer token. */
    public boolean usesBearerToken() {
        return httpTransport && this != AZURE;
    }

    public String defaultAuthenticationHeader() {
        return this == AZURE ? "api-key" : "Authorization";
    }

    /**
     * Resolves a configured provider name. Names are matched case-insensitively and tolerate {@code -} and
     * {@code _} separators (for example {@code openai-compatible} or {@code openai_compatible}).
     * Unknown names fall back to {@link #SCRIPTED} so a typo degrades to the offline client instead of failing
     * application startup.
     */
    public static LlmProvider fromConfiguration(String value) {
        if (value == null || value.isBlank()) {
            return SCRIPTED;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values())
                .filter(provider -> provider.name().equals(normalized))
                .findFirst()
                .orElse(SCRIPTED);
    }
}
