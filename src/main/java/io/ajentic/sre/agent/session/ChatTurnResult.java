package io.ajentic.sre.agent.session;

import io.ajentic.sre.agent.llm.TokenUsage;
import io.ajentic.sre.agent.llm.ToolCall;
import io.ajentic.sre.rl.Observation;

import java.util.List;

public record ChatTurnResult(String answer, List<ToolCall> toolCalls, Observation observation, TokenUsage usage) {

    public ChatTurnResult {
        toolCalls = List.copyOf(toolCalls);
    }
}
