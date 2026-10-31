package io.ajentic.sre.api.payload;

import io.ajentic.sre.agent.llm.TokenUsage;
import io.ajentic.sre.agent.llm.ToolCall;
import io.ajentic.sre.simulation.Observation;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response for a chat turn or one-shot investigation: the agent's answer, the resulting observation, the tool calls
 * it made, and the token usage consumed.
 */
@Schema(description = "Response for a chat turn or one-shot investigation.")
public record ChatResponse(
        @Schema(description = "Session id.", example = "b3f1c2d4-...")
        String sessionId,

        @Schema(description = "The agent's answer.", example = "Root cause: database connection pool saturated.")
        String answer,

        @Schema(description = "The observation after the turn.")
        Observation observation,

        @Schema(description = "The tool calls made this turn.")
        List<ToolCall> toolCalls,

        @Schema(description = "Token usage consumed by the turn.")
        TokenUsage usage) {

    public ChatResponse {
        toolCalls = List.copyOf(toolCalls);
    }
}
