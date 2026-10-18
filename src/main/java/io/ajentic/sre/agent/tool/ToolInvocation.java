package io.ajentic.sre.agent.tool;

import java.util.Map;
import java.util.Optional;

public record ToolInvocation(String name, Map<String, Object> arguments) {

    public ToolInvocation {
        arguments = Map.copyOf(arguments);
    }

    public Optional<String> optionalString(String key) {
        Object value = arguments.get(key);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    public String string(String key) {
        return optionalString(key)
                .orElseThrow(() -> new IllegalArgumentException("Missing required argument '" + key + "'"));
    }
}
