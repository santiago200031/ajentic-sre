package io.ajentic.sre.connectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Sidecar connector internal HTTP endpoints. Validates HMAC signatures, timestamps and nonces,
 * and logs mutating operations to the audit store.
 */
@Path("/internal/v1")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SidecarInternalResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SidecarSecurity security;
    private final SidecarAuditLogger auditLogger;
    private final boolean allowMutations;

    @Inject
    public SidecarInternalResource(
            SidecarAuditLogger auditLogger,
            @ConfigProperty(name = "ajentic.sidecar.secret", defaultValue = "default-sidecar-secret") String secret,
            @ConfigProperty(name = "ajentic.sidecar.allow-mutations", defaultValue = "true") boolean allowMutations) {
        this.security = new SidecarSecurity(secret);
        this.auditLogger = auditLogger;
        this.allowMutations = allowMutations;
    }

    @POST
    @Path("/{cloud}/telemetry")
    public SidecarDto.TelemetryResponse queryTelemetry(
            @PathParam("cloud") String cloud,
            @HeaderParam("X-Ajentic-Timestamp") String timestamp,
            @HeaderParam("X-Ajentic-Nonce") String nonce,
            @HeaderParam("X-Ajentic-Signature") String signature,
            SidecarDto.TelemetryRequest request) {

        verifyRequest(timestamp, nonce, signature, serialize(request));

        SidecarDto.SignalItem sample = new SidecarDto.SignalItem(
                request.signal(),
                request.service(),
                "Sidecar observed telemetry sample for " + request.service() + " on " + cloud,
                Map.of("errorRate", 0.08, "p95LatencyMs", 350.0));

        return new SidecarDto.TelemetryResponse(List.of(sample), 0.08, 350.0);
    }

    @POST
    @Path("/{cloud}/changes")
    public SidecarDto.ChangesResponse inspectChanges(
            @PathParam("cloud") String cloud,
            @HeaderParam("X-Ajentic-Timestamp") String timestamp,
            @HeaderParam("X-Ajentic-Nonce") String nonce,
            @HeaderParam("X-Ajentic-Signature") String signature,
            SidecarDto.ChangesRequest request) {

        verifyRequest(timestamp, nonce, signature, serialize(request));

        SidecarDto.ChangeItem item = new SidecarDto.ChangeItem(
                request.service(),
                "DEPLOY",
                "Deployment update on " + cloud + " for " + request.service(),
                Instant.now().toString(),
                "image: " + request.service() + ":v2");

        return new SidecarDto.ChangesResponse(List.of(item));
    }

    @POST
    @Path("/{cloud}/remediate")
    public SidecarDto.RemediationResponse remediate(
            @PathParam("cloud") String cloud,
            @HeaderParam("X-Ajentic-Timestamp") String timestamp,
            @HeaderParam("X-Ajentic-Nonce") String nonce,
            @HeaderParam("X-Ajentic-Signature") String signature,
            SidecarDto.RemediationRequest request) {

        verifyRequest(timestamp, nonce, signature, serialize(request));

        if (!allowMutations) {
            return new SidecarDto.RemediationResponse(false, "Sidecar mutation rejected: sidecar is configured in read-only mode.");
        }

        String summary = "Executed " + request.remediation() + " on " + cloud + " for service " + request.service();
        auditLogger.recordMutation(
                request.actor(),
                request.remediation(),
                request.service() + "@" + cloud,
                "SUCCESS",
                summary);

        return new SidecarDto.RemediationResponse(true, summary);
    }

    @POST
    @Path("/chat/{provider}/notify")
    public SidecarDto.NotifyResponse notify(
            @PathParam("provider") String provider,
            @HeaderParam("X-Ajentic-Timestamp") String timestamp,
            @HeaderParam("X-Ajentic-Nonce") String nonce,
            @HeaderParam("X-Ajentic-Signature") String signature,
            SidecarDto.NotifyRequest request) {

        verifyRequest(timestamp, nonce, signature, serialize(request));
        return new SidecarDto.NotifyResponse(true, "Notification delivered to " + provider);
    }

    private void verifyRequest(String timestamp, String nonce, String signature, String body) {
        if (!security.verify(timestamp, nonce, signature, body)) {
            throw new WebApplicationException("Unauthorized sidecar request: invalid signature, timestamp, or replayed nonce", Response.Status.UNAUTHORIZED);
        }
    }

    private static String serialize(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "";
        }
    }
}
