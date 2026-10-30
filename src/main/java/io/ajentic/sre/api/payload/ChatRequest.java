package io.ajentic.sre.api.payload;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to send a chat turn to an investigation session.
 */
@Schema(description = "Request to send a chat turn to an investigation session.")
public record ChatRequest(
        @NotBlank(message = "message must not be blank.")
        @Schema(description = "User message that advances the agent.", example = "Why is checkout-api latency spiking?")
        String message) {
}
