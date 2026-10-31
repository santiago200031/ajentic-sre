package io.ajentic.sre.agent.approval;

import java.time.Instant;
import java.util.Map;

/**
 * A mutating tool call that was deferred behind operator approval. The placeholder tool result pushed to the
 * conversation sits at {@code conversationIndex} until the approval is decided (executed, denied, or expired).
 */
public record PendingApproval(
        String approvalId,
        String sessionId,
        int conversationIndex,
        String toolCallId,
        String toolName,
        Map<String, Object> arguments,
        Instant requestedAt,
        Instant expiresAt) {

    public PendingApproval {
        arguments = Map.copyOf(arguments);
    }

    public PendingApproval withIndex(int index) {
        return new PendingApproval(approvalId, sessionId, index, toolCallId, toolName, arguments, requestedAt, expiresAt);
    }
}
