package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to register a cron-driven drill.
 */
@Schema(description = "Request to register a cron-driven drill.")
public record CreateScheduleRequest(
        @NotBlank(message = "name must not be blank.")
        @Schema(description = "Schedule name.", example = "hourly-checkout-drill")
        String name,

        @NotBlank(message = "cron must not be blank.")
        @Schema(description = "Cron expression.", example = "0 * * * *")
        String cron,

        @Schema(description = "Scenario to drill for.", example = "checkout-latency-db-pool")
        String scenarioId) {
}
