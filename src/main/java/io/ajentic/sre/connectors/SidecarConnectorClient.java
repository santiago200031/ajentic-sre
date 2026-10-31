package io.ajentic.sre.connectors;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Type-safe Quarkus REST Client for the sidecar connectors contract.
 * Configured via {@code ajentic.sidecar/mp-rest/url} in application.properties.
 */
@RegisterRestClient(configKey = "ajentic.sidecar")
@Path("/internal/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface SidecarConnectorClient {

    @POST
    @Path("/{cloud}/telemetry")
    SidecarDto.TelemetryResponse queryTelemetry(
            @PathParam("cloud") String cloud,
            @HeaderParam("X-Ajentic-Timestamp") String timestamp,
            @HeaderParam("X-Ajentic-Nonce") String nonce,
            @HeaderParam("X-Ajentic-Signature") String signature,
            SidecarDto.TelemetryRequest request);

    @POST
    @Path("/{cloud}/changes")
    SidecarDto.ChangesResponse inspectChanges(
            @PathParam("cloud") String cloud,
            @HeaderParam("X-Ajentic-Timestamp") String timestamp,
            @HeaderParam("X-Ajentic-Nonce") String nonce,
            @HeaderParam("X-Ajentic-Signature") String signature,
            SidecarDto.ChangesRequest request);

    @POST
    @Path("/{cloud}/remediate")
    SidecarDto.RemediationResponse remediate(
            @PathParam("cloud") String cloud,
            @HeaderParam("X-Ajentic-Timestamp") String timestamp,
            @HeaderParam("X-Ajentic-Nonce") String nonce,
            @HeaderParam("X-Ajentic-Signature") String signature,
            SidecarDto.RemediationRequest request);

    @POST
    @Path("/chat/{provider}/notify")
    SidecarDto.NotifyResponse notify(
            @PathParam("provider") String provider,
            @HeaderParam("X-Ajentic-Timestamp") String timestamp,
            @HeaderParam("X-Ajentic-Nonce") String nonce,
            @HeaderParam("X-Ajentic-Signature") String signature,
            SidecarDto.NotifyRequest request);
}
