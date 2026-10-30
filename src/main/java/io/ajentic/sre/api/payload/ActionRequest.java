package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to apply a single manual action to an episode environment.
 */
@Schema(description = "Request to apply a single manual action to an episode environment.")
public record ActionRequest(
        @NotNull
        @Schema(description = "Action type: inspect, alert, remediate, or diagnose.", example = "remediate")
        String type,

        @Schema(description = "Signal or metric the action targets.", example = "LATENCY")
        String signal,

        @Schema(description = "Service the action targets.", example = "checkout-api")
        String service,

        @Schema(description = "Runbook id to reference.", example = "rb-db-pool")
        String runbookId,

        @Schema(description = "Remediation to apply.", example = "Scale checkout-api and raise the pool limit")
        String remediation,

        @Schema(description = "Diagnosed root cause.", example = "Database connection pool saturated")
        String rootCause) {
}
