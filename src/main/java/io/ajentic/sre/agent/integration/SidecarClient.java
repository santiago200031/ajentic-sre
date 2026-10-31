package io.ajentic.sre.agent.integration;

import io.ajentic.sre.connectors.SidecarConnectorClient;
import io.ajentic.sre.connectors.SidecarDto;
import io.ajentic.sre.connectors.SidecarSecurity;
import io.ajentic.sre.connectors.SidecarUnavailableException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Client for communicating with the least-privilege sidecar connector service.
 * Signs all outbound HTTP requests using HMAC-SHA256 with timestamp and nonces.
 */
@ApplicationScoped
public class SidecarClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SidecarConnectorClient restClient;
    private final SidecarSecurity security;

    @Inject
    public SidecarClient(
            @RestClient SidecarConnectorClient restClient,
            @ConfigProperty(name = "ajentic.sidecar.secret", defaultValue = "default-sidecar-secret") String secret) {
        this.restClient = restClient;
        this.security = new SidecarSecurity(secret);
    }

    public SidecarDto.TelemetryResponse queryTelemetry(String cloud, String service, String signal) {
        try {
            SidecarDto.TelemetryRequest req = new SidecarDto.TelemetryRequest(service, signal);
            String timestamp = Instant.now().toString();
            String nonce = UUID.randomUUID().toString();
            String sig = security.sign(timestamp, nonce, toJson(req));
            return restClient.queryTelemetry(cloud, timestamp, nonce, sig, req);
        } catch (Exception e) {
            throw new SidecarUnavailableException("Sidecar connector unavailable for telemetry: " + e.getMessage(), e);
        }
    }

    public SidecarDto.ChangesResponse inspectChanges(String cloud, String service, int windowMinutes) {
        try {
            SidecarDto.ChangesRequest req = new SidecarDto.ChangesRequest(service, windowMinutes);
            String timestamp = Instant.now().toString();
            String nonce = UUID.randomUUID().toString();
            String sig = security.sign(timestamp, nonce, toJson(req));
            return restClient.inspectChanges(cloud, timestamp, nonce, sig, req);
        } catch (Exception e) {
            throw new SidecarUnavailableException("Sidecar connector unavailable for changes: " + e.getMessage(), e);
        }
    }

    public SidecarDto.RemediationResponse remediate(String cloud, String service, String remediation, String actor) {
        try {
            SidecarDto.RemediationRequest req = new SidecarDto.RemediationRequest(service, remediation, actor);
            String timestamp = Instant.now().toString();
            String nonce = UUID.randomUUID().toString();
            String sig = security.sign(timestamp, nonce, toJson(req));
            return restClient.remediate(cloud, timestamp, nonce, sig, req);
        } catch (Exception e) {
            throw new SidecarUnavailableException("Sidecar connector unavailable for remediation: " + e.getMessage(), e);
        }
    }

    public SidecarDto.NotifyResponse notify(String provider, String title, String body, String severity) {
        try {
            SidecarDto.NotifyRequest req = new SidecarDto.NotifyRequest(title, body, severity);
            String timestamp = Instant.now().toString();
            String nonce = UUID.randomUUID().toString();
            String sig = security.sign(timestamp, nonce, toJson(req));
            return restClient.notify(provider, timestamp, nonce, sig, req);
        } catch (Exception e) {
            throw new SidecarUnavailableException("Sidecar connector unavailable for notifications: " + e.getMessage(), e);
        }
    }

    private static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "";
        }
    }
}
