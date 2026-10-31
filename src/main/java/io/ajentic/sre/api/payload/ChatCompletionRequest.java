package io.ajentic.sre.api.payload;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "OpenAI-compatible chat completion request.")
public record ChatCompletionRequest(
        @Schema(description = "ID of the model to use.", example = "Cyber-Tiel-Coder-35B-A3B-UD-Q4_K_XL")
        String model,

        @Schema(description = "A list of messages comprising the conversation so far.")
        List<ChatMessageDto> messages,

        @Schema(description = "If set, partial message deltas will be sent as server-sent events.")
        Boolean stream,

        @Schema(description = "Sampling temperature.")
        Double temperature,

        @Schema(description = "Maximum number of tokens to generate.")
        Integer maxTokens,

        @Schema(description = "A unique identifier representing your end-user or session.", example = "user-1234")
        String user
) {
    public boolean isStream() {
        return stream != null && stream;
    }
}
