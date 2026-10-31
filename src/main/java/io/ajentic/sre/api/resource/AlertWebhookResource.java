package io.ajentic.sre.api.resource;

import io.ajentic.sre.agent.alerts.AlertNormalizer;
import io.ajentic.sre.agent.alerts.AlertSeverity;
import io.ajentic.sre.agent.alerts.NormalizedAlert;
import io.ajentic.sre.agent.mcp.SreAgentPrompts;
import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.session.ChatTurnResult;
import io.ajentic.sre.agent.session.SessionService;
import io.ajentic.sre.api.payload.AlertWebhookResponse;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ScenarioRepository;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;
import java.util.Optional;

/**
 * Ingests multi-cloud alerts and runs a one-shot code remediation ReAct investigation. Alerts below the configured
 * severity threshold or lacking a repository are acknowledged with 202 Accepted and skipped with zero LLM calls.
 */
@Path("/api/v1/webhooks/alerts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Tag(name = "Webhooks", description = "Multi-cloud alert intake and automated code remediation")
@RunOnVirtualThread
public class AlertWebhookResource {

    private static final int MAX_ITERATIONS = 8;

    private final AlertNormalizer normalizer;
    private final SessionService sessions;
    private final ScenarioRepository scenarios;
    private final AlertSeverity minSeverity;
    private final String defaultScenario;
    private final Optional<String> model;

    @Inject
    public AlertWebhookResource(
            AlertNormalizer normalizer,
            SessionService sessions,
            ScenarioRepository scenarios,
            @ConfigProperty(name = "ajentic.alerts.min-severity", defaultValue = "ERROR") String minSeverityStr,
            @ConfigProperty(name = "ajentic.alerts.default-scenario", defaultValue = "orders-bad-deployment") String defaultScenario,
            @ConfigProperty(name = "ajentic.alerts.model") Optional<String> model) {
        this.normalizer = normalizer;
        this.sessions = sessions;
        this.scenarios = scenarios;
        this.minSeverity = AlertSeverity.from(minSeverityStr);
        this.defaultScenario = defaultScenario;
        this.model = model;
    }

    @POST
    @Operation(summary = "Intake a multi-cloud alert and execute automated code remediation",
            description = "Normalizes alerts from CloudWatch, Azure Monitor, Alertmanager, or direct JSON. "
                    + "Ignores alerts below min-severity or lacking a repository (202 Accepted). "
                    + "Otherwise runs a one-shot ReAct investigation to inspect code, create a branch, commit a fix and regression test, and open a PR (200 OK).")
    @APIResponse(responseCode = "200", description = "Alert processed and code remediation attempted",
            content = @Content(schema = @Schema(implementation = AlertWebhookResponse.class)))
    @APIResponse(responseCode = "202", description = "Alert ignored (below severity threshold or missing repository)",
            content = @Content(schema = @Schema(implementation = AlertWebhookResponse.class)))
    public Response receiveAlert(Map<String, Object> payload) {
        NormalizedAlert alert = normalizer.normalize(payload);

        if (!alert.severity().isAtLeast(minSeverity)) {
            return Response.status(Response.Status.ACCEPTED)
                    .entity(AlertWebhookResponse.ignored(
                            alert.severity().name(),
                            alert.repository(),
                            "Alert severity " + alert.severity() + " is below minimum threshold " + minSeverity))
                    .build();
        }

        if (alert.repository() == null || alert.repository().isBlank()) {
            return Response.status(Response.Status.ACCEPTED)
                    .entity(AlertWebhookResponse.ignored(
                            alert.severity().name(),
                            null,
                            "No repository could be resolved for alert"))
                    .build();
        }

        String prompt = SreAgentPrompts.buildCodeRemediationPrompt(
                alert.repository(),
                alert.branch(),
                alert.title(),
                alert.message(),
                alert.stackTrace(),
                alert.fingerprint());

        String scenarioId = scenarioFor(alert.service()).orElse(defaultScenario);
        AgentSessionConfig config = new AgentSessionConfig(
                scenarioId,
                42L,
                model.orElse(null),
                MAX_ITERATIONS);

        AgentSession session = sessions.create(config);
        try {
            ChatTurnResult result = session.chat(prompt);
            AlertWebhookResponse response = new AlertWebhookResponse(
                    "PROCESSED",
                    alert.severity().name(),
                    alert.repository(),
                    session.id(),
                    result.answer(),
                    result.toolCalls(),
                    result.usage());
            return Response.ok(response).build();
        } finally {
            sessions.delete(session.id());
        }
    }

    private Optional<String> scenarioFor(String service) {
        if (service == null || service.isBlank()) {
            return Optional.empty();
        }
        return scenarios.all().stream()
                .filter(s -> s.services().stream().anyMatch(spec -> spec.name().equalsIgnoreCase(service)))
                .map(Scenario::id)
                .findFirst();
    }
}
