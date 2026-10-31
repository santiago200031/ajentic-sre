package io.ajentic.sre.api.webhook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Alert intake endpoints. Each provider posts its native payload; the service normalizes it, deduplicates by
 * fingerprint, runs the investigation synchronously, and returns the report inline. When
 * {@code ajentic.webhooks.secret} is set, the matching header must carry it:
 * {@code X-Alertmanager-Secret}, {@code Authorization: Token ptoken=<secret>} (PagerDuty), or
 * {@code X-SNS-Secret} (CloudWatch SNS).
 */
@Path("/api/v1/webhooks")
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@io.smallrye.common.annotation.RunOnVirtualThread
public class WebhookResource {

    private final AlertIntakeService intake;
    private final Optional<String> secret;

    public WebhookResource(
            AlertIntakeService intake,
            @ConfigProperty(name = "ajentic.webhooks.secret") Optional<String> secret) {
        this.intake = intake;
        this.secret = secret;
    }

    @POST
    @Path("/alertmanager")
    public Response alertmanager(JsonNode payload, @HeaderParam("X-Alertmanager-Secret") String secretHeader) {
        requireSecret(secretHeader);
        return intake(Alert.fromAlertmanager(payload));
    }

    @POST
    @Path("/pagerduty")
    public Response pagerduty(JsonNode payload, @HeaderParam("Authorization") String authorization) {
        if (secret.isPresent() && !("Token ptoken=" + secret.get()).equals(authorization)) {
            throw new WebhookException(401, "Intake secret mismatch.");
        }
        return intake(Alert.fromPagerDuty(payload));
    }

    @POST
    @Path("/cloudwatch-sns")
    public Response cloudwatchSns(JsonNode payload, @HeaderParam("X-SNS-Secret") String secretHeader) {
        requireSecret(secretHeader);
        return intake(Alert.fromCloudWatchSns(payload));
    }

    @GET
    public Response reports() {
        return Response.ok(intake.reports().values().stream()
                .map(report -> View.of(report.alert(), report.scenarioId(), report.sessionId(),
                        report.answer(), report.duplicate()))
                .toList())
                .build();
    }

    private Response intake(Alert alert) {
        AlertIntakeService.IntakeResult result = intake.intake(alert);
        Alert reportAlert = result.report().alert();
        return Response.ok()
                .entity(View.of(reportAlert, result.scenarioId(), result.report().sessionId(),
                        result.report().answer(), result.duplicate()))
                .build();
    }

    private void requireSecret(String provided) {
        if (secret.isPresent() && !secret.get().equals(provided)) {
            throw new WebhookException(401, "Intake secret mismatch.");
        }
    }

    public record View(
            String fingerprint,
            String source,
            String service,
            String summary,
            String severity,
            String scenarioId,
            String sessionId,
            boolean duplicate,
            String report) {

        static View of(Alert alert, String scenarioId, String sessionId, String report, boolean duplicate) {
            return new View(alert.fingerprint(), alert.source(), alert.service(), alert.summary(), alert.severity(),
                    scenarioId, sessionId, duplicate, report);
        }
    }
}
