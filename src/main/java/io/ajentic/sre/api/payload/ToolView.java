package io.ajentic.sre.api.payload;

import io.ajentic.sre.agent.tool.ToolDescriptor;

import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * View of a registered SRE tool: its name, description, parameter schema, and required capability.
 */
@Schema(description = "View of a registered SRE tool.")
public record ToolView(
        @Schema(description = "Tool name.", example = "query_telemetry")
        String name,

        @Schema(description = "Tool description.", example = "Query telemetry signals for a service.")
        String description,

        @Schema(description = "JSON schema of the tool parameters.")
        Map<String, Object> parameters,

        @Schema(description = "Capability required to run the tool.", example = "OBSERVE")
        String capability) {

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
