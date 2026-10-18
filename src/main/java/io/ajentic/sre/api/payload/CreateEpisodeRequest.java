package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotBlank;

public record CreateEpisodeRequest(@NotBlank String scenarioId, Long seed) {
}
