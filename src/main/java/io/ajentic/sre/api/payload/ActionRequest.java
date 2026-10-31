package io.ajentic.sre.api.payload;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Request to apply a single manual action to an episode environment.
 */
@Schema(description = "Request to apply a single manual action to an episode environment.")
public record ActionRequest(
        @NotNull
        @JsonAlias({"type", "kind"})
        @JsonProperty("type")
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
        String rootCause,

        @Schema(description = "Lookback window in minutes (inspect_recent_changes) or hop radius (correlate_anomalies).")
        Integer window,

        @Schema(description = "Service names for correlate_anomalies.")
        List<String> services) {
}
