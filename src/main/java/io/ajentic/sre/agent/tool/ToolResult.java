package io.ajentic.sre.agent.tool;

import java.util.Map;

public record ToolResult(boolean success, String content, Map<String, Object> data) {

    public ToolResult {
        data = Map.copyOf(data);
    }

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, Map.of());
    }

    public static ToolResult ok(String content, Map<String, Object> data) {
        return new ToolResult(true, content, data);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, message, Map.of());
    }
}
