package io.ajentic.sre.api.resource;

import io.ajentic.sre.agent.alerts.AlertNormalizer;
import io.ajentic.sre.agent.alerts.AlertSeverity;
import io.ajentic.sre.agent.alerts.NormalizedAlert;
import io.ajentic.sre.agent.github.CiFeedbackLoop;
import io.ajentic.sre.agent.github.CiStatusKind;
import io.ajentic.sre.agent.github.GitHubClient;
import io.ajentic.sre.agent.github.RemediationRecord;
import io.ajentic.sre.agent.github.RemediationStore;
import io.ajentic.sre.agent.llm.ChatMessage;
import io.ajentic.sre.agent.llm.ChatRole;
import io.ajentic.sre.agent.llm.ToolCall;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_IGNORED = "IGNORED";
    public static final String CI_PENDING_LABEL = "CI pending";

    private static final int MAX_ITERATIONS = 8;
    private static final long DEFAULT_SEED = 42L;
    private static final Pattern PR_URL_PATTERN =
            Pattern.compile("https://github\\.com/([A-Za-z0-9._-]+/[A-Za-z0-9._-]+)/pull/(\\d+)");
    private static final Pattern BRANCH_PATTERN = Pattern.compile("ajentic/[A-Za-z0-9._-]+");

    private final AlertNormalizer normalizer;
    private final SessionService sessions;
    private final ScenarioRepository scenarios;
    private final RemediationStore remediationStore;
    private final GitHubClient github;
    private final AlertSeverity minSeverity;
    private final String defaultScenario;
    private final Optional<String> model;

    @Inject
    public AlertWebhookResource(
            AlertNormalizer normalizer,
            SessionService sessions,
            ScenarioRepository scenarios,
            RemediationStore remediationStore,
            GitHubClient github,
            @ConfigProperty(name = "ajentic.alerts.min-severity", defaultValue = "ERROR") String minSeverityStr,
            @ConfigProperty(name = "ajentic.alerts.default-scenario", defaultValue = "orders-bad-deployment") String defaultScenario,
            @ConfigProperty(name = "ajentic.alerts.model") Optional<String> model) {
        this.normalizer = normalizer;
        this.sessions = sessions;
        this.scenarios = scenarios;
        this.remediationStore = remediationStore;
        this.github = github;
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
                DEFAULT_SEED,
                model.orElse(null),
                MAX_ITERATIONS);

        AgentSession session = sessions.create(config);
        try {
            ChatTurnResult result = session.chat(prompt);
            RemediationRecord remediation = extractRemediation(alert, session, result);
            String ciStatus = remediation != null ? remediation.ciStatus() : null;
            String answer = result.answer();
            if (remediation != null && answer != null
                    && !answer.toLowerCase().contains("ci pending")
                    && !answer.toLowerCase().contains("ci status")) {
                answer = answer + "\nCI status: "
                        + (CiStatusKind.PENDING.value().equalsIgnoreCase(ciStatus) ? CI_PENDING_LABEL : ciStatus);
            }

            AlertWebhookResponse response = new AlertWebhookResponse(
                    STATUS_PROCESSED,
                    alert.severity().name(),
                    alert.repository(),
                    session.id(),
                    answer,
                    result.toolCalls(),
                    result.usage(),
                    remediation,
                    ciStatus);
            return Response.ok(response).build();
        } finally {
            sessions.delete(session.id());
        }
    }

    private RemediationRecord extractRemediation(NormalizedAlert alert, AgentSession session, ChatTurnResult result) {
        String repo = alert.repository();
        String branch = null;
        String commitSha = null;
        int prNumber = 0;
        String prUrl = null;

        // 1. Inspect conversation messages for tool calls and tool results
        for (ChatMessage msg : session.conversation().messages()) {
            if (msg.toolCalls() != null) {
                for (ToolCall tc : msg.toolCalls()) {
                    if ("github_create_branch".equals(tc.name())) {
                        Object b = tc.arguments().get("newBranch");
                        if (b != null) {
                            branch = b.toString();
                        }
                    } else if ("github_commit_file".equals(tc.name())) {
                        Object b = tc.arguments().get("branch");
                        if (b != null && branch == null) {
                            branch = b.toString();
                        }
                    } else if ("github_get_ci_status".equals(tc.name())) {
                        Object r = tc.arguments().get("ref");
                        if (r != null && commitSha == null) {
                            commitSha = r.toString();
                        }
                    } else if ("github_create_pull_request".equals(tc.name())) {
                        Object h = tc.arguments().get("head");
                        if (h != null && branch == null) {
                            branch = h.toString();
                        }
                    }
                }
            }
            if (msg.role() == ChatRole.TOOL && msg.content() != null) {
                if (commitSha == null && msg.content().contains("(") && msg.content().contains(")")) {
                    int start = msg.content().lastIndexOf('(');
                    int end = msg.content().lastIndexOf(')');
                    if (start >= 0 && end > start) {
                        commitSha = msg.content().substring(start + 1, end).trim();
                    }
                }
                Matcher matcher = PR_URL_PATTERN.matcher(msg.content());
                if (matcher.find()) {
                    prUrl = matcher.group(0);
                    prNumber = Integer.parseInt(matcher.group(2));
                }
            }
        }

        // 2. Fall back to parsing the answer text
        if (prUrl == null && result.answer() != null) {
            Matcher matcher = PR_URL_PATTERN.matcher(result.answer());
            if (matcher.find()) {
                prUrl = matcher.group(0);
                prNumber = Integer.parseInt(matcher.group(2));
            }
        }

        if (prUrl == null) {
            return null;
        }

        if (branch == null && result.answer() != null) {
            Matcher m = BRANCH_PATTERN.matcher(result.answer());
            if (m.find()) {
                branch = m.group(0);
            }
        }
        if (branch == null) {
            String fp8 = alert.fingerprint() != null && alert.fingerprint().length() >= 8
                    ? alert.fingerprint().substring(0, 8) : "12345678";
            branch = "ajentic/fix-" + fp8;
        }

        // 3. Single non-blocking snapshot call to get initial CI status
        String ciStatus = CiStatusKind.PENDING.value();
        try {
            String ref = commitSha != null && !commitSha.isBlank() ? commitSha : branch;
            GitHubClient.CiStatus ci = github.getCiStatus(repo, ref);
            ciStatus = CiFeedbackLoop.computeStatus(ci);
        } catch (Exception ignored) {
            ciStatus = CiStatusKind.PENDING.value();
        }

        RemediationRecord record = new RemediationRecord(repo, branch, commitSha, prNumber, prUrl, ciStatus);
        remediationStore.record(record);
        return record;
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
