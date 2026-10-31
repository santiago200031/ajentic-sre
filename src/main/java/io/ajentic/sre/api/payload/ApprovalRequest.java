package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to decide a pending mutating tool call.
 */
@Schema(description = "Request to approve or deny a pending mutating tool call.")
public record ApprovalRequest(
        @NotBlank(message = "approvalId must not be blank.")
        @Schema(description = "Approval id returned with the PENDING_APPROVAL tool result.", example = "9f1c…")
        String approvalId,

        @NotBlank(message = "decision must be 'approve' or 'deny'.")
        @Schema(description = "Operator decision.", example = "approve")
        String decision,

        @Schema(description = "Optional operator note attached to the decision.")
        String note) {
}
