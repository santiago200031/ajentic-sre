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

    /**
     * Enables the ordered failover list below. Disabled by default so the offline suite never talks to extra
     * endpoints.
     */
    @WithDefault("false")
    boolean fallbackEnabled();

    /**
     * Ordered fallback endpoints (e.g. Bedrock/Vertex/Azure OpenAI/OpenRouter gateways). Each entry is
     * pipe-separated: {@code base-url|model|api-key|api-version|api-key-header}; fields after the base URL may
     * be left empty. Separate multiple endpoints with a comma. Keys are never logged.
     */
    Optional<String> fallbackEndpoints();

    default LlmProvider providerType() {
        return LlmProvider.fromConfiguration(provider());
    }

    default boolean azure() {
        return providerType() == LlmProvider.AZURE;
    }

    /**
     * Builds a standalone configuration for an explicit endpoint (used for fallback clients and tests).
     */
    static LlmProperties of(String provider, String baseUrl, Optional<String> apiKey, String model, Duration timeout,
                            double temperature, String chatCompletionsPath, Optional<String> apiVersion,
                            Optional<String> apiKeyHeader) {
        return new LlmProperties() {
            @Override
            public String provider() {
                return provider;
            }

            @Override
            public String baseUrl() {
                return baseUrl;
            }

            @Override
            public Optional<String> apiKey() {
                return apiKey;
            }

            @Override
            public String model() {
                return model;
            }

            @Override
            public Duration timeout() {
                return timeout;
            }

            @Override
            public double temperature() {
                return temperature;
            }

            @Override
            public String chatCompletionsPath() {
                return chatCompletionsPath;
            }

            @Override
            public Optional<String> apiVersion() {
                return apiVersion;
            }

            @Override
            public Optional<String> apiKeyHeader() {
                return apiKeyHeader;
            }

            @Override
            public boolean live() {
                return false;
            }

            @Override
            public boolean fallbackEnabled() {
                return false;
            }

            @Override
            public Optional<String> fallbackEndpoints() {
                return Optional.empty();
            }
        };
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
