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
        return Map.of(
                "ajentic.llm.provider", "openai-compatible",
                "ajentic.llm.base-url", "http://localhost:" + server.port() + "/v1",
                "ajentic.llm.api-key", "",
                "ajentic.llm.model", "wm-default",
                "ajentic.llm.timeout", "30s",
                "ajentic.llm.temperature", "0.0");
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
