package io.ajentic.sre.test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the {@code @QuarkusTest} suite against WireMock over the real {@code OpenAiCompatibleLlmClient} HTTP path,
 * using the structured stubs under {@code src/test/resources/wiremock}. Set {@code -Dajentic.llm.live=true} (or the
 * {@code live-llm} Maven profile) to bypass WireMock and pass the caller's LLM configuration straight through to a
 * live OpenAI-compatible endpoint instead.
 */
public class WireMockLlmTestResource implements QuarkusTestResourceLifecycleManager {

    private static final List<String> LIVE_KEYS = List.of(
            "quarkus.langchain4j.openai.base-url",
            "quarkus.langchain4j.openai.api-key",
            "quarkus.langchain4j.openai.chat-model.model-name",
            "quarkus.langchain4j.openai.timeout",
            "quarkus.langchain4j.openai.chat-model.temperature",
            "ajentic.llm.provider",
            "ajentic.llm.base-url",
            "ajentic.llm.api-key",
            "ajentic.llm.model",
            "ajentic.llm.timeout",
            "ajentic.llm.temperature",
            "ajentic.llm.api-version",
            "ajentic.llm.api-key-header");

    private static WireMockServer server;

    @Override
    public Map<String, String> start() {
        if (live()) {
            return liveConfiguration();
        }
        synchronized (WireMockLlmTestResource.class) {
            if (server == null || !server.isRunning()) {
                server = new WireMockServer(WireMockConfiguration.options()
                        .dynamicPort()
                        .usingFilesUnderClasspath("wiremock"));
                server.start();
            }
        }
        return Map.ofEntries(
                Map.entry("quarkus.langchain4j.openai.base-url", "http://localhost:" + server.port() + "/v1"),
                Map.entry("quarkus.langchain4j.openai.api-key", "wm-test-key"),
                Map.entry("quarkus.langchain4j.openai.chat-model.model-name", "wm-default"),
                Map.entry("quarkus.langchain4j.openai.timeout", "30s"),
                Map.entry("quarkus.langchain4j.openai.chat-model.temperature", "0.0"),
                Map.entry("ajentic.llm.provider", "openai-compatible"),
                Map.entry("ajentic.llm.base-url", "http://localhost:" + server.port() + "/v1"),
                Map.entry("ajentic.llm.api-key", ""),
                Map.entry("ajentic.llm.model", "wm-default"),
                Map.entry("ajentic.llm.timeout", "30s"),
                Map.entry("ajentic.llm.temperature", "0.0"),
                Map.entry("ajentic.github.base-url", "http://localhost:" + server.port()),
                Map.entry("ajentic.github.token", "gh_test_token"));
    }

    @Override
    public void stop() {
        synchronized (WireMockLlmTestResource.class) {
            if (server != null) {
                server.stop();
                server = null;
            }
        }
    }

    public static boolean live() {
        return Boolean.parseBoolean(System.getProperty("ajentic.llm.live", "false"));
    }

    public static WireMockServer server() {
        return server;
    }

    public static void resetScenarios() {
        if (server != null && server.isRunning()) {
            server.resetScenarios();
        }
    }

    private static Map<String, String> liveConfiguration() {
        Map<String, String> configuration = new LinkedHashMap<>();
        for (String key : LIVE_KEYS) {
            String value = property(key);
            if (value != null && !value.isBlank()) {
                configuration.put(key, value);
            }
        }
        configuration.putIfAbsent("ajentic.llm.provider", "openai-compatible");
        if (configuration.containsKey("ajentic.llm.base-url")) {
            configuration.putIfAbsent("quarkus.langchain4j.openai.base-url", configuration.get("ajentic.llm.base-url"));
        }
        if (configuration.containsKey("ajentic.llm.api-key")) {
            configuration.putIfAbsent("quarkus.langchain4j.openai.api-key", configuration.get("ajentic.llm.api-key"));
        }
        if (configuration.containsKey("ajentic.llm.model")) {
            configuration.putIfAbsent("quarkus.langchain4j.openai.chat-model.model-name", configuration.get("ajentic.llm.model"));
        }
        if (configuration.containsKey("ajentic.llm.temperature")) {
            configuration.putIfAbsent("quarkus.langchain4j.openai.chat-model.temperature", configuration.get("ajentic.llm.temperature"));
        }
        return configuration;
    }

    /** Reads a system property first, then the conventional {@code AJENTIC_LLM_*} environment variable. */
    private static String property(String key) {
        String value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
    }
}
