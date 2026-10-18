package io.ajentic.sre.agent.mcp;

import java.util.Arrays;
import java.util.Optional;

/** The JSON-RPC methods the Model Context Protocol server answers. */
public enum McpMethod {

    INITIALIZE("initialize"),
    PING("ping"),
    TOOLS_LIST("tools/list"),
    TOOLS_CALL("tools/call"),
    RESOURCES_LIST("resources/list"),
    RESOURCES_READ("resources/read"),
    PROMPTS_LIST("prompts/list"),
    PROMPTS_GET("prompts/get");

    private final String wireName;

    McpMethod(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<McpMethod> fromWireName(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(method -> method.wireName.equals(value))
                .findFirst();
    }
}
