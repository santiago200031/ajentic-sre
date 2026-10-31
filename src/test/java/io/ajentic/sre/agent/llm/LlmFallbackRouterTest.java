package io.ajentic.sre.agent.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Failover behaviour of the composite client, exercised offline against two WireMock endpoints.
 */
@QuarkusTest
class LlmFallbackRouterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OK_BODY = """
            {"id":"c1","object":"chat.completion","model":"m1","choices":[{"index":0,
            "message":{"role":"assistant","content":"fallback answered"},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}}
            """;

    private static WireMockServer primary;
    private static WireMockServer fallback;

    @org.junit.jupiter.api.BeforeAll
    static void start() {
        primary = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        primary.start();
        fallback = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        fallback.start();
    }

    @org.junit.jupiter.api.AfterAll
    static void stop() {
        primary.stop();
        fallback.stop();
    }

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        primary.resetAll();
        fallback.resetAll();
    }

    private static LangChain4jLlmClient client(WireMockServer server, String model) {
        return new LangChain4jLlmClient(LlmProperties.of(
                "openai-compatible", server.baseUrl(), Optional.empty(),
                model, Duration.ofSeconds(10), 0.0, "/chat/completions", Optional.empty(), Optional.empty()), MAPPER);
    }

    private static LlmRequest request() {
        return new LlmRequest(List.of(ChatMessage.user("Say hello.")), List.of(), null);
    }

    private void stub(WireMockServer server, int status) {
        server.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(status).withBody("boom")));
    }

    @org.junit.jupiter.api.Test
    void primaryFailureFailsOverToTheFirstHealthyEndpoint() {
        stub(primary, 500);
        fallback.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(OK_BODY)));
        CompositeLlmClient client = new CompositeLlmClient(client(primary, "m1"), List.of(client(fallback, "m1")));

        LlmResponse response = client.complete(request());

        assertEquals("fallback answered", response.content());
        assertEquals(12, response.usage().totalTokens());
    }

    @org.junit.jupiter.api.Test
    void allEndpointsDownYieldsOneClearError() {
        stub(primary, 500);
        stub(fallback, 503);
        CompositeLlmClient client = new CompositeLlmClient(client(primary, "m1"), List.of(client(fallback, "m1")));

        LlmException exception = assertThrows(LlmException.class, () -> client.complete(request()));
        assertTrue(exception.getMessage().startsWith("All LLM endpoints failed"), exception.getMessage());
    }

    @org.junit.jupiter.api.Test
    void healthyPrimaryNeverTouchesTheFallback() {
        primary.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(OK_BODY)));
        stub(fallback, 500);
        CompositeLlmClient client = new CompositeLlmClient(client(primary, "m1"), List.of(client(fallback, "m1")));

        LlmResponse response = client.complete(request());

        assertEquals("fallback answered", response.content());
        assertEquals(0, fallback.countRequestsMatching(postRequestedFor(urlEqualTo("/chat/completions")).build()).getCount());
    }
}
