package io.ajentic.sre.agent.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ajentic.sre.agent.tool.ToolDescriptor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OpenAiCompatibleLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {
    };

    private final LlmProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final ExecutorService executor;

    public OpenAiCompatibleLlmClient(LlmProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .executor(executor)
                .build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        WireChatRequest body = new WireChatRequest(
                resolveModel(request),
                properties.temperature(),
                request.messages().stream().map(this::toWire).toList(),
                request.tools().isEmpty() ? null : request.tools().stream()
                        .map(OpenAiCompatibleLlmClient::toWire).toList());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(endpoint())
                .timeout(properties.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        properties.authenticationHeader().ifPresent(header -> builder.header(header, properties.authenticationValue().orElse("")));
        HttpRequest httpRequest = builder.build();

        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new LlmException("LLM provider returned " + response.statusCode() + ": " + response.body());
            }
            try {
                return parse(response.body());
            } catch (RuntimeException exception) {
                throw new LlmException("Could not parse the LLM response", exception);
            }
        } catch (IOException exception) {
            throw new LlmException("LLM request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM request interrupted", exception);
        }
    }

    private URI endpoint() {
        String base = trimTrailingSlash(properties.baseUrl());
        String path = properties.chatCompletionsPath().startsWith("/")
                ? properties.chatCompletionsPath()
                : "/" + properties.chatCompletionsPath();
        String url = base + path;
        return properties.apiVersion()
                .filter(version -> !version.isBlank())
                .map(version -> URI.create(url + (url.contains("?") ? "&" : "?") + "api-version=" + version))
                .orElseGet(() -> URI.create(url));
    }

    private String resolveModel(LlmRequest request) {
        return request.model() == null || request.model().isBlank() ? properties.model() : request.model();
    }

    private WireMessage toWire(ChatMessage message) {
        return new WireMessage(
                message.role().name().toLowerCase(Locale.ROOT),
                message.content(),
                message.toolCallId(),
                message.toolCalls().isEmpty()
                        ? null
                        : message.toolCalls().stream()
                                .map(call -> new WireToolCall(call.id(), "function",
                                        new WireFunctionCall(call.name(), mapper.writeValueAsString(call.arguments()))))
                                .toList());
    }

    private static WireTool toWire(ToolDescriptor descriptor) {
        return new WireTool("function",
                new WireFunctionDefinition(descriptor.name(), descriptor.description(), descriptor.parameters()));
    }

    private LlmResponse parse(String body) {
        WireResponse response = mapper.readValue(body, WireResponse.class);
        WireChoice choice = response.choices().isEmpty() ? WireChoice.empty() : response.choices().getFirst();
        WireResponseMessage message = choice.message() == null ? WireResponseMessage.empty() : choice.message();

        List<ToolCall> toolCalls = message.toolCalls() == null
                ? List.of()
                : message.toolCalls().stream()
                        .map(call -> new ToolCall(
                                call.id(),
                                call.function() == null ? "" : call.function().name(),
                                parseArguments(call.function() == null ? null : call.function().arguments())))
                        .toList();

        WireUsage usage = response.usage() == null ? WireUsage.empty() : response.usage();
        return new LlmResponse(
                message.content(),
                toolCalls,
                new TokenUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens()),
                choice.finishReason() == null ? "stop" : choice.finishReason());
    }

    private Map<String, Object> parseArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, Object> arguments = new LinkedHashMap<>(mapper.readValue(raw, ARGUMENTS));
        return Map.copyOf(arguments);
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record WireChatRequest(
            String model,
            double temperature,
            List<WireMessage> messages,
            List<WireTool> tools) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record WireMessage(
            String role,
            String content,
            @JsonProperty("tool_call_id") String toolCallId,
            @JsonProperty("tool_calls") List<WireToolCall> toolCalls) {
    }

    private record WireToolCall(String id, String type, WireFunctionCall function) {
    }

    /** OpenAI, Azure, AWS and llama.cpp all require {@code function.arguments} to be a JSON-encoded string. */
    private record WireFunctionCall(String name, String arguments) {
    }

    private record WireTool(String type, WireFunctionDefinition function) {
    }

    private record WireFunctionDefinition(String name, String description, Map<String, Object> parameters) {
    }

    private record WireResponse(List<WireChoice> choices, WireUsage usage) {
    }

    private record WireChoice(WireResponseMessage message, @JsonProperty("finish_reason") String finishReason) {

        static WireChoice empty() {
            return new WireChoice(null, null);
        }
    }

    private record WireResponseMessage(
            String content,
            @JsonProperty("tool_calls") List<WireResponseToolCall> toolCalls) {

        static WireResponseMessage empty() {
            return new WireResponseMessage(null, null);
        }
    }

    private record WireResponseToolCall(String id, WireResponseFunction function) {
    }

    private record WireResponseFunction(String name, String arguments) {
    }

    private record WireUsage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {

        static WireUsage empty() {
            return new WireUsage(0, 0, 0);
        }
    }
}
