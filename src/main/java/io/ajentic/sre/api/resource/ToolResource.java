package io.ajentic.sre.api.resource;

import io.ajentic.sre.agent.tool.ToolRegistry;
import io.ajentic.sre.api.payload.ToolView;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Registered SRE tool catalog.
 */
@Path("/api/v1/tools")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Tag(name = "Tools", description = "Registered SRE tool catalog")
@RunOnVirtualThread
public class ToolResource {

    private final ToolRegistry tools;

    @Inject
    public ToolResource(ToolRegistry tools) {
        this.tools = tools;
    }

    @GET
    @Operation(summary = "List tools", description = "Returns the registered tool descriptors.")
    @APIResponse(responseCode = "200", description = "The tool list",
            content = @Content(schema = @Schema(implementation = ToolView.class)))
    public List<ToolView> list() {
        return tools.descriptors().stream().map(ToolView::from).toList();
    }
}
