package io.ajentic.sre.api.payload;

import io.ajentic.sre.agent.tool.ToolDescriptor;

import java.util.Map;

public record ToolView(String name, String description, Map<String, Object> parameters, String capability) {

    public ToolView {
        parameters = Map.copyOf(parameters);
    }

    public static ToolView from(ToolDescriptor descriptor) {
        return new ToolView(
                descriptor.name(),
                descriptor.description(),
                descriptor.parameters(),
                descriptor.capability().name());
    }
}
