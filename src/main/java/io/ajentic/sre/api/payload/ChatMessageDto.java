package io.ajentic.sre.api.payload;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A message in a chat completion.")
public record ChatMessageDto(
        @Schema(description = "The role of the author: system, user, or assistant.", example = "user")
        String role,

        @Schema(description = "The contents of the message.", example = "Why is checkout latency high?")
        String content,

        @Schema(description = "Optional name for the author.")
        String name
) {
    public ChatMessageDto(String role, String content) {
        this(role, content, null);
    }
}
