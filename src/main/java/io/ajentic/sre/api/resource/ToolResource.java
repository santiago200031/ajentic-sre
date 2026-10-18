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

@Path("/api/v1/tools")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class ToolResource {

    private final ToolRegistry tools;

    @Inject
    public ToolResource(ToolRegistry tools) {
        this.tools = tools;
    }

    @GET
    public List<ToolView> list() {
        return tools.descriptors().stream().map(ToolView::from).toList();
    }
}
