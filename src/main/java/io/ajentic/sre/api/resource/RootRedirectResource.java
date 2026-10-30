package io.ajentic.sre.api.resource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Sends `GET /` straight to the Swagger UI so opening the server host lands on interactive API documentation.
 */
@Path("/")
@ApplicationScoped
@Tag(name = "Agent")
public class RootRedirectResource {

    private static final String SWAGGER_UI = "/q/swagger-ui";

    @GET
    @Operation(summary = "Redirect the server root to the Swagger UI", description = "303 See Other to /q/swagger-ui.")
    public Response redirectToSwagger() {
        return Response.seeOther(URI.create(SWAGGER_UI)).build();
    }
}
