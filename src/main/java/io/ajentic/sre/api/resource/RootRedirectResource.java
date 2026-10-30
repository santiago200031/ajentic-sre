package io.ajentic.sre.api.resource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Sends `GET /` straight to the Swagger UI so opening the server host lands on interactive API documentation.
 */
@Path("/")
@ApplicationScoped
public class RootRedirectResource {

    private static final String SWAGGER_UI = "/q/swagger-ui";

    @GET
    @Operation(hidden = true)
    public Response redirectToSwagger() {
        return Response.seeOther(URI.create(SWAGGER_UI)).build();
    }
}
