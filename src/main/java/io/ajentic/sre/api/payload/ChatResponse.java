package io.ajentic.sre.api.payload;

import io.ajentic.sre.agent.llm.TokenUsage;
import io.ajentic.sre.agent.llm.ToolCall;
import io.ajentic.sre.rl.Observation;

import java.util.List;

public record ChatResponse(
        String sessionId,
        String answer,
        Observation observation,
        List<ToolCall> toolCalls,
        TokenUsage usage) {

    public ChatResponse {
        toolCalls = List.copyOf(toolCalls);
    }
}
