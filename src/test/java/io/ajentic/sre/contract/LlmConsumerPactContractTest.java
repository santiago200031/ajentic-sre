package io.ajentic.sre.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import io.ajentic.sre.agent.llm.ChatMessage;
import io.ajentic.sre.agent.llm.LlmException;
import io.ajentic.sre.agent.llm.LlmProperties;
import io.ajentic.sre.agent.llm.LlmProvider;
import io.ajentic.sre.agent.llm.LlmRequest;
import io.ajentic.sre.agent.llm.LlmResponse;
import io.ajentic.sre.agent.llm.LangChain4jLlmClient;
import io.ajentic.sre.agent.llm.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Formally pins the OpenAI-compatible HTTP contract that {@code ajentic-sre} consumes.
 */
@QuarkusTest
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "openai-compatible-llm-provider", port = "0", pactVersion = PactSpecVersion.V3)
class LlmConsumerPactContractTest {

    private static final String TOOL = "query_telemetry";

    @Pact(consumer = "ajentic-sre", provider = "openai-compatible-llm-provider")
    public RequestResponsePact initialPromptReturnsToolCall(PactDslWithProvider builder) {
        return builder
                .given("a model that answers with a tool call")
                .uponReceiving("an initial prompt")
                .path("/chat/completions")
                .method("POST")
                .matchHeader("Authorization", "Bearer test-key")
                .matchHeader("Content-Type", "application/json")
                .body("""
                        {"model":"gpt-6-luna","temperature":0.0,"messages":[
                          {"role":"user","content":"Why are orders failing?"}]}""")
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", "application/json")
                .body("""
                        {"id":"chatcmpl-tool","choices":[{"index":0,"message":{
                          "tool_calls":[{"id":"c1","type":"function","function":{
                            "name":"query_telemetry","arguments":"{\\"signal\\":\\"LOGS\\"}"}}]},
                          "finish_reason":"tool_calls"}],
                          "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""")
                .toPact();
    }

    @Pact(consumer = "ajentic-sre", provider = "openai-compatible-llm-provider")
    public RequestResponsePact toolResultReturnsFinalAnswer(PactDslWithProvider builder) {
        return builder
                .given("a model that answers after a tool result")
                .uponReceiving("an assistant tool call followed by its result")
                .path("/chat/completions")
                .method("POST")
                .matchHeader("Authorization", "Bearer test-key")
                .matchHeader("Content-Type", "application/json")
                // The assistant tool call must carry function.arguments as a JSON string, not an object.
                .body("""
                        {"model":"gpt-6-luna","temperature":0.0,"messages":[
                          {"role":"user","content":"Why are orders failing?"},
                          {"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{
                            "name":"query_telemetry","arguments":"{\\"signal\\":\\"LOGS\\"}"}}]},
                          {"role":"tool","content":"no error logs","tool_call_id":"c1"}]}""")
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", "application/json")
                .body("""
                        {"id":"chatcmpl-final","choices":[{"index":0,"message":{
                          "role":"assistant","content":"Rolled back orders-api."},"finish_reason":"stop"}],
                          "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""")
                .toPact();
    }

    @Pact(consumer = "ajentic-sre", provider = "openai-compatible-llm-provider")
    public RequestResponsePact providerError(PactDslWithProvider builder) {
        return builder
                .given("the model backend is unavailable")
                .uponReceiving("a chat completion request while the backend is down")
                .path("/chat/completions")
                .method("POST")
                .body("""
                        {"model":"gpt-6-luna","temperature":0.0,"messages":[
                          {"role":"user","content":"Why are orders failing?"}]}""")
                .willRespondWith()
                .status(500)
                .body("{\"error\":{\"message\":\"backend exploded\"}}")
                .toPact();
    }

    @Pact(consumer = "ajentic-sre", provider = "azure-openai-llm-provider")
    public RequestResponsePact azureChatCompletion(PactDslWithProvider builder) {
        return builder
                .given("an Azure OpenAI deployment")
                .uponReceiving("a chat completion request with an api-version")
                .path("/chat/completions")
                .query("api-version=2025-01-01-preview")
                .method("POST")
                .matchHeader("api-key", "azure-key")
                .matchHeader("Content-Type", "application/json")
                .body("""
                        {"model":"gpt-6-luna","temperature":0.0,"messages":[
                          {"role":"user","content":"Why are orders failing?"}]}""")
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", "application/json")
                .body("""
                        {"id":"chatcmpl-azure","choices":[{"index":0,"message":{
                          "role":"assistant","content":"Azure deployment answered."},"finish_reason":"stop"}],
                          "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""")
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "initialPromptReturnsToolCall", pactVersion = PactSpecVersion.V3)
    void consumesAToolCallResponse(MockServer mockServer) {
        LlmResponse response = client(openAi(mockServer)).complete(new LlmRequest(
                List.of(ChatMessage.user("Why are orders failing?")),
                List.of(),
                null));

        assertTrue(response.hasToolCalls());
        assertEquals(TOOL, response.toolCalls().getFirst().name());
        assertEquals("LOGS", response.toolCalls().getFirst().arguments().get("signal"));

        // The client was built from the "openai_compatible" alias above; a typo must degrade to the offline client.
        assertEquals(LlmProvider.AZURE, LlmProvider.fromConfiguration("azure"));
        assertEquals(LlmProvider.SCRIPTED, LlmProvider.fromConfiguration("does-not-exist"));
    }

    @Test
    @PactTestFor(pactMethod = "toolResultReturnsFinalAnswer", pactVersion = PactSpecVersion.V3)
    void sendsFunctionArgumentsAsAJsonString(MockServer mockServer) {
        LlmResponse response = client(openAi(mockServer)).complete(new LlmRequest(
                List.of(
                        ChatMessage.user("Why are orders failing?"),
                        ChatMessage.assistant(null, List.of(new ToolCall("c1", TOOL, Map.of("signal", "LOGS")))),
                        ChatMessage.tool("c1", "no error logs")),
                List.of(),
                null));

        assertEquals("Rolled back orders-api.", response.content());
    }

    @Test
    @PactTestFor(pactMethod = "providerError", pactVersion = PactSpecVersion.V3)
    void surfacesProviderErrors(MockServer mockServer) {
        assertThrows(LlmException.class, () -> client(openAi(mockServer)).complete(new LlmRequest(
                List.of(ChatMessage.user("Why are orders failing?")),
                List.of(),
                null)));
    }

    @Test
    @PactTestFor(pactMethod = "azureChatCompletion", providerName = "azure-openai-llm-provider", pactVersion = PactSpecVersion.V3)
    void supportsAzureApiVersionAndApiKeyHeader(MockServer mockServer) {
        LlmResponse response = client(azure(mockServer)).complete(new LlmRequest(
                List.of(ChatMessage.user("Why are orders failing?")),
                List.of(),
                null));

        assertEquals("Azure deployment answered.", response.content());
    }

    private static LangChain4jLlmClient client(LlmProperties properties) {
        return new LangChain4jLlmClient(properties, JsonMapper.builder().build());
    }

    private static LlmProperties openAi(MockServer mockServer) {
        return new TestProperties("openai_compatible", mockServer.getUrl(), "test-key", null, null);
    }

    private static LlmProperties azure(MockServer mockServer) {
        return new TestProperties("azure", mockServer.getUrl(), "azure-key", "2025-01-01-preview", null);
    }

    private static final class TestProperties implements LlmProperties {

        private final String provider;
        private final String baseUrl;
        private final String apiKey;
        private final String apiVersion;
        private final String apiKeyHeader;

        private TestProperties(String provider, String baseUrl, String apiKey, String apiVersion, String apiKeyHeader) {
            this.provider = provider;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.apiVersion = apiVersion;
            this.apiKeyHeader = apiKeyHeader;
        }

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
            return Optional.ofNullable(apiKey);
        }

        @Override
        public String model() {
            return "gpt-6-luna";
        }

        @Override
        public Duration timeout() {
            return Duration.ofSeconds(5);
        }

        @Override
        public double temperature() {
            return 0.0;
        }

        @Override
        public String chatCompletionsPath() {
            return "/chat/completions";
        }

        @Override
        public Optional<String> apiVersion() {
            return Optional.ofNullable(apiVersion);
        }

        @Override
        public Optional<String> apiKeyHeader() {
            return Optional.ofNullable(apiKeyHeader);
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
        public java.util.Optional<String> fallbackEndpoints() {
            return java.util.Optional.empty();
        }
    }
}
