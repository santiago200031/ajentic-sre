package io.ajentic.sre.api.payload;

import io.ajentic.sre.agent.github.RemediationRecord;
import io.ajentic.sre.agent.llm.TokenUsage;
import io.ajentic.sre.agent.llm.ToolCall;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Response for an alert webhook submission: the outcome status (PROCESSED or IGNORED), severity,
 * repository, session id, answer, tool calls, token usage, optional remediation record, and CI status.
 */
@Schema(description = "Response for an alert webhook submission.")
public record AlertWebhookResponse(
        @Schema(description = "Outcome status: PROCESSED or IGNORED.", example = "PROCESSED")
        String status,

        @Schema(description = "Normalized alert severity.", example = "ERROR")
        String severity,

        @Schema(description = "Target repository owner/name.", example = "acme/checkout-service")
        String repository,

        @Schema(description = "Session id.", example = "b3f1c2d4-...")
        String sessionId,

        @Schema(description = "The agent's answer.", example = "Root cause analyzed and PR opened.")
        String answer,

        @Schema(description = "The tool calls made this turn.")
        List<ToolCall> toolCalls,

        @Schema(description = "Token usage consumed by the turn.")
        TokenUsage usage,

        @Schema(description = "Remediation record if a PR was opened.")
        RemediationRecord remediation,

        @Schema(description = "CI snapshot status: PENDING, SUCCESS, FAILURE, or null.", example = "PENDING")
        String ciStatus) {

    public AlertWebhookResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public AlertWebhookResponse(String status, String severity, String repository, String sessionId,
                                String answer, List<ToolCall> toolCalls, TokenUsage usage) {
        this(status, severity, repository, sessionId, answer, toolCalls, usage, null, null);
    }

    public static AlertWebhookResponse ignored(String severity, String repository, String reason) {
        return new AlertWebhookResponse("IGNORED", severity, repository, null, reason, List.of(), TokenUsage.empty(),
                null, null);
    }
}
