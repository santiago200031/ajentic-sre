package io.ajentic.sre.agent.tool;

import java.util.List;
import java.util.Map;

public final class ToolSchemas {

    private ToolSchemas() {
    }

    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false);
    }

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> enumeration(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }
}
