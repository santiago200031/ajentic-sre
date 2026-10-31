package io.ajentic.sre.api.payload;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "OpenAI-compatible chat completion response.")
public record ChatCompletionResponse(
        @Schema(description = "A unique identifier for the chat completion.")
        String id,

        @Schema(description = "The object type, always 'chat.completion'.")
        String object,

        @Schema(description = "The Unix timestamp (in seconds) of when the chat completion was created.")
        long created,

        @Schema(description = "The model used.")
        String model,

        @Schema(description = "A list of chat completion choices.")
        List<Choice> choices,

        @Schema(description = "Usage statistics for the completion request.")
        Usage usage
) {
    public record Choice(
            int index,
            ChatMessageDto message,
            String finish_reason
    ) {}

    public record Usage(
            long prompt_tokens,
            long completion_tokens,
            long total_tokens
    ) {}
}
