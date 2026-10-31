package io.ajentic.sre.agent.approval;

/**
 * The outcome of deciding a pending approval. {@code executed} is true only when the deferred mutating call was
 * actually run; repeats of the same decision are idempotent and report the stored outcome.
 */
public record ApprovalOutcome(String approvalId, boolean approved, boolean executed, String result, String note) {
}
