package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(@NotBlank String scenarioId, Long seed, String model) {
}
