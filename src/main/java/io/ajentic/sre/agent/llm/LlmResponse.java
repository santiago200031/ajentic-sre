package io.ajentic.sre.agent.llm;

import java.util.List;

public record LlmResponse(String content, List<ToolCall> toolCalls, TokenUsage usage, String finishReason) {

    public LlmResponse {
        toolCalls = List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public static LlmResponse finalAnswer(String content) {
        return new LlmResponse(content, List.of(), TokenUsage.empty(), "stop");
    }

    public static LlmResponse toolCalls(List<ToolCall> toolCalls) {
        return new LlmResponse(null, toolCalls, TokenUsage.empty(), "tool_calls");
    }
}
