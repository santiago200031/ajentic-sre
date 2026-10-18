package io.ajentic.sre.agent.llm;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "ajentic.llm")
public interface LlmProperties {

    @WithDefault("scripted")
    String provider();

    @WithDefault("https://api.openai.com/v1")
    String baseUrl();

    @WithDefault("")
    Optional<String> apiKey();

    @WithDefault("gpt-6-luna")
    String model();

    @WithDefault("60s")
    Duration timeout();

    @WithDefault("0.0")
    double temperature();

    @WithDefault("/chat/completions")
    String chatCompletionsPath();

    Optional<String> apiVersion();

    Optional<String> apiKeyHeader();

    /**
     * Test-only toggle: when {@code true} the end-to-end suite talks to a live OpenAI-compatible endpoint instead of
     * the bundled WireMock stubs.
     */
    @WithDefault("false")
    boolean live();

    default LlmProvider providerType() {
        return LlmProvider.fromConfiguration(provider());
    }

    default boolean azure() {
        return providerType() == LlmProvider.AZURE;
    }

    /**
     * Resolves the header used for authentication. Azure OpenAI uses {@code api-key}; every other
     * OpenAI-compatible provider (OpenAI, AWS gateways, llama.cpp, vLLM, Ollama) uses
     * {@code Authorization: Bearer <key>}. When no key is configured the header is omitted entirely.
     */
    default Optional<String> authenticationHeader() {
        if (apiKey().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(apiKeyHeader().orElseGet(providerType()::defaultAuthenticationHeader));
    }

    default Optional<String> authenticationValue() {
        if (apiKey().isEmpty()) {
            return Optional.empty();
        }
        String key = apiKey().get();
        return Optional.of(providerType().usesBearerToken() && apiKeyHeader().isEmpty() ? "Bearer " + key : key);
    }
}
