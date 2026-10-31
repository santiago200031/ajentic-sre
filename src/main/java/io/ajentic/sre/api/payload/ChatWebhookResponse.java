package io.ajentic.sre.api.payload;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Inbound chatops webhook response.")
public record ChatWebhookResponse(
        @Schema(description = "Slack response type: in_channel or ephemeral.", example = "in_channel")
        String response_type,

        @Schema(description = "The message text to display in chat.", example = "Investigation complete: root cause found.")
        String text
) {
    public static ChatWebhookResponse inChannel(String text) {
        return new ChatWebhookResponse("in_channel", text);
    }

    public static ChatWebhookResponse ephemeral(String text) {
        return new ChatWebhookResponse("ephemeral", text);
    }
}
