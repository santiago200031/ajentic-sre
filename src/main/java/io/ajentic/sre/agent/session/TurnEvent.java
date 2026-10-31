package io.ajentic.sre.agent.session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One typed event of a ReAct turn, streamed to SSE clients as it is produced.
 */
public record TurnEvent(String type, Map<String, Object> payload) {

    public static TurnEvent thought(String content) {
        return new TurnEvent("thought", Map.of("content", content));
    }

    public static TurnEvent toolCall(String name, Map<String, Object> arguments) {
        return new TurnEvent("tool_call", Map.of("name", name, "arguments", arguments));
    }

    public static TurnEvent toolResult(String name, String result) {
        return new TurnEvent("tool_result", Map.of("name", name, "result", truncate(result)));
    }

    public static TurnEvent usage(long promptTokens, long completionTokens, long totalTokens) {
        return new TurnEvent("usage", Map.of(
                "promptTokens", promptTokens,
                "completionTokens", completionTokens,
                "totalTokens", totalTokens));
    }

    public static TurnEvent done(String answer, long promptTokens, long completionTokens, long totalTokens) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", answer);
        payload.put("promptTokens", promptTokens);
        payload.put("completionTokens", completionTokens);
        payload.put("totalTokens", totalTokens);
        return new TurnEvent("done", payload);
    }

    public static TurnEvent error(String message) {
        return new TurnEvent("error", Map.of("message", message));
    }

    private static String truncate(String value) {
        String text = value == null ? "" : value;
        return text.length() <= 400 ? text : text.substring(0, 400) + "...";
    }
}
