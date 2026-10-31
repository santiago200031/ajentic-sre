package io.ajentic.sre.test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * Second WireMock endpoint for the LLM failover tests. It serves {@code /fb/chat/completions} with a scripted
 * 200 response and registers the fallback endpoint in the test configuration.
 */
public class FallbackLlmTestResource implements QuarkusTestResourceLifecycleManager {

    public static final String FALLBACK_OK_BODY = """
            {"id":"c1","object":"chat.completion","model":"wm-fallback","choices":[{"index":0,
            "message":{"role":"assistant","content":"The fallback endpoint answered."},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":9,"completion_tokens":6,"total_tokens":15}}
            """;

    private static WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(post(urlEqualTo("/fb/chat/completions"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(FALLBACK_OK_BODY)));
        return Map.of(
                "ajentic.llm.fallback-enabled", "true",
                "ajentic.llm.fallback-endpoints", server.baseUrl() + "/fb|wm-fallback");
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    public static WireMockServer server() {
        return server;
    }
}
