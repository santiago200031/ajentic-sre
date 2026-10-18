package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String message) {
}
