package io.ajentic.sre.agent.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.ajentic.sre.agent.tool.ToolDescriptor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Standard LLM client powered by LangChain4j's {@link ChatModel}.
 */
public final class LangChain4jLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {};

    private final ChatModel chatModel;
    private final ObjectMapper mapper;

    public LangChain4jLlmClient(ChatModel chatModel, ObjectMapper mapper) {
        this.chatModel = chatModel;
        this.mapper = mapper;
    }

    public LangChain4jLlmClient(LlmProperties properties, ObjectMapper mapper) {
        this(buildOpenAiChatModel(properties), mapper);
    }

    private static OpenAiChatModel buildOpenAiChatModel(LlmProperties properties) {
        String baseUrl = properties.baseUrl();
        if (properties.apiVersion().isPresent() && !properties.apiVersion().get().isBlank()) {
            String version = properties.apiVersion().get();
            if (!baseUrl.contains("api-version=")) {
                baseUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "api-version=" + version;
            }
        }

        var builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(properties.apiKey().filter(k -> !k.isBlank()).orElse("local"))
                .modelName(properties.model())
                .temperature(properties.temperature())
                .timeout(properties.timeout());

        Map<String, String> headers = new java.util.LinkedHashMap<>();
        properties.authenticationHeader().ifPresent(header ->
                properties.authenticationValue().ifPresent(val ->
                        headers.put(header, val)));
        properties.apiKeyHeader().ifPresent(header ->
                properties.apiKey().ifPresent(key ->
                        headers.put(header, key)));
        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
        }

        return builder.build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        try {
            ChatRequest.Builder builder = ChatRequest.builder()
                    .messages(toChatMessages(request.messages()))
                    .toolSpecifications(toToolSpecifications(request.tools()));

            if (request.model() != null && !request.model().isBlank()) {
                builder.modelName(request.model());
            }

            ChatResponse response = chatModel.chat(builder.build());
            return toLlmResponse(response);
        } catch (RuntimeException exception) {
            throw new LlmException("LLM request failed: " + exception.getMessage(), exception);
        }
    }

    private List<dev.langchain4j.data.message.ChatMessage> toChatMessages(List<ChatMessage> messages) {
        List<dev.langchain4j.data.message.ChatMessage> result = new ArrayList<>(messages.size());
        for (ChatMessage msg : messages) {
            switch (msg.role()) {
                case SYSTEM -> result.add(SystemMessage.from(msg.content() == null ? "" : msg.content()));
                case USER -> result.add(UserMessage.from(msg.content() == null ? "" : msg.content()));
                case ASSISTANT -> {
                    if (msg.toolCalls().isEmpty()) {
                        result.add(AiMessage.from(msg.content() == null ? "" : msg.content()));
                    } else {
                        List<ToolExecutionRequest> toolRequests = msg.toolCalls().stream()
                                .map(call -> ToolExecutionRequest.builder()
                                        .id(call.id())
                                        .name(call.name())
                                        .arguments(serializeArguments(call.arguments()))
                                        .build())
                                .toList();
                        result.add(AiMessage.from(msg.content(), toolRequests));
                    }
                }
                case TOOL -> result.add(ToolExecutionResultMessage.from(
                        msg.toolCallId(), null, msg.content() == null ? "" : msg.content()));
            }
        }
        return result;
    }

    private String serializeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    private List<ToolSpecification> toToolSpecifications(List<ToolDescriptor> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<ToolSpecification> specs = new ArrayList<>(tools.size());
        for (ToolDescriptor desc : tools) {
            ToolSpecification.Builder builder = ToolSpecification.builder()
                    .name(desc.name())
                    .description(desc.description());

            if (desc.parameters() != null && !desc.parameters().isEmpty()) {
                try {
                    builder.parameters((JsonObjectSchema) JsonSchemaElementJsonUtils.fromMap(desc.parameters()));
                } catch (Exception ignored) {
                    builder.parameters(JsonObjectSchema.builder().build());
                }
            } else {
                builder.parameters(JsonObjectSchema.builder().build());
            }
            specs.add(builder.build());
        }
        return specs;
    }

    private LlmResponse toLlmResponse(ChatResponse response) {
        AiMessage ai = response.aiMessage();
        String content = ai != null ? ai.text() : null;

        List<ToolCall> toolCalls = new ArrayList<>();
        if (ai != null && ai.hasToolExecutionRequests()) {
            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                toolCalls.add(new ToolCall(req.id(), req.name(), parseArguments(req.arguments())));
            }
        }

        TokenUsage usage = TokenUsage.empty();
        if (response.tokenUsage() != null) {
            dev.langchain4j.model.output.TokenUsage tu = response.tokenUsage();
            usage = new TokenUsage(
                    tu.inputTokenCount() == null ? 0 : tu.inputTokenCount(),
                    tu.outputTokenCount() == null ? 0 : tu.outputTokenCount(),
                    tu.totalTokenCount() == null ? 0 : tu.totalTokenCount());
        }

        String finishReason = response.finishReason() != null
                ? response.finishReason().name().toLowerCase() : "stop";

        return new LlmResponse(content, toolCalls, usage, finishReason);
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(arguments, ARGUMENTS);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
