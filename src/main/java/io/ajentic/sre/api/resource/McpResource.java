package io.ajentic.sre.api.resource;

import io.ajentic.sre.agent.mcp.McpProtocolHandler;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Streamable HTTP transport for the Model Context Protocol. {@code POST} accepts a single JSON-RPC 2.0 request or a
 * batch array and returns the JSON-RPC response; notifications (requests without an {@code id}) are answered with
 * {@code 202 Accepted}. {@code GET} exposes discovery metadata and {@code DELETE} clears the active default episode.
 */
@Path("/api/v1/mcp")
@ApplicationScoped
@Tag(name = "MCP", description = "Model Context Protocol HTTP streamable transport")
@RunOnVirtualThread
public class McpResource {

    private final McpProtocolHandler handler;

    @Inject
    public McpResource(McpProtocolHandler handler) {
        this.handler = handler;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Handle a JSON-RPC request", description = "Processes one JSON-RPC 2.0 request or batch; notifications return 202.")
    @APIResponse(responseCode = "200", description = "The JSON-RPC response",
            content = @Content(schema = @Schema(type = SchemaType.STRING)))
    @APIResponse(responseCode = "202", description = "Accepted notification")
    public Response handleJsonRpc(String requestBody, @HeaderParam("Mcp-Session-Id") String sessionId) {
        String scope = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
        String response = handler.handle(requestBody, scope);
        if (response == null) {
            return Response.accepted().header("Mcp-Session-Id", scope).build();
        }
        return Response.ok(response, MediaType.APPLICATION_JSON_TYPE)
                .header("Mcp-Session-Id", scope)
                .build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Describe the MCP server", description = "Returns server name, protocol version, and capabilities.")
    @APIResponse(responseCode = "200", description = "The server description",
            content = @Content(schema = @Schema(type = SchemaType.OBJECT)))
    public Map<String, Object> describe() {
        return Map.of(
                "server", McpProtocolHandler.SERVER_NAME,
                "protocolVersion", McpProtocolHandler.PROTOCOL_VERSION,
                "transport", "streamable-http",
                "capabilities", List.of("tools", "resources", "prompts"));
    }

    @DELETE
    @ResponseStatus(204)
    @Operation(summary = "Reset MCP sessions", description = "Clears the default episode, or a single session when a session id is given.")
    @APIResponse(responseCode = "204", description = "Reset")
    public void reset(@HeaderParam("Mcp-Session-Id") String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            handler.reset();
        } else {
            handler.reset(sessionId);
        }
    }
}
