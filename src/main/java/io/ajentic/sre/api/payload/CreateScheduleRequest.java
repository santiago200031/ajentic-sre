package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotBlank;

public record CreateScheduleRequest(@NotBlank String name, @NotBlank String cron, String scenarioId) {
}
