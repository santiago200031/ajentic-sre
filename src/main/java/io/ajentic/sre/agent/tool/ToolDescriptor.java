package io.ajentic.sre.agent.tool;

import java.util.Map;

public record ToolDescriptor(String name, String description, Map<String, Object> parameters, Capability capability) {

    public ToolDescriptor {
        parameters = Map.copyOf(parameters);
    }

    public ToolDescriptor(String name, String description, Map<String, Object> parameters) {
        this(name, description, parameters, Capability.READ);
    }

    public boolean readOnly() {
        return capability == Capability.READ;
    }
}
