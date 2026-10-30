package io.ajentic.sre.agent.tool.builtin;

import io.ajentic.sre.agent.integration.IncidentSummary;
import io.ajentic.sre.agent.integration.NotificationChannel;
import io.ajentic.sre.agent.tool.AgentTool;
import io.ajentic.sre.agent.tool.Capability;
import io.ajentic.sre.agent.tool.ToolBox;
import io.ajentic.sre.agent.tool.ToolContext;
import io.ajentic.sre.agent.tool.ToolParam;
import io.ajentic.sre.agent.tool.ToolResult;
import io.ajentic.sre.rl.ObservedSignal;
import io.ajentic.sre.rl.StepResult;
import io.ajentic.sre.rl.TelemetrySignal;
import io.ajentic.sre.rl.action.ApplyRemediation;
import io.ajentic.sre.rl.action.DeclareDiagnosis;
import io.ajentic.sre.rl.action.ListRemediations;
import io.ajentic.sre.rl.action.QueryTelemetry;
import io.ajentic.sre.rl.action.ReadRunbook;
import io.ajentic.sre.rl.action.RemediationKind;
import io.ajentic.sre.rl.episode.EpisodeCursor;
import io.ajentic.sre.rl.episode.EpisodeHandle;
import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.rl.episode.EpisodeService;
import io.ajentic.sre.rl.simulation.Runbook;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ScenarioRepository;
import io.ajentic.sre.schedule.drill.IncidentDrillRunner;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The built-in SRE capabilities, declared as annotated methods instead of a class per tool. {@code
 * CdiToolRegistry} derives the JSON Schema from these signatures and binds the model's arguments to them.
 */
@ToolBox
@Unremovable
public class BuiltInSreTools {

    private final ScenarioRepository scenarios;
    private final NotificationChannel channel;
    private final EpisodeService episodes;
    private final EpisodeCursor cursor;
    private final IncidentDrillRunner drills;
    private final String defaultScenarioId;

    @Inject
    public BuiltInSreTools(
            ScenarioRepository scenarios,
            NotificationChannel channel,
            EpisodeService episodes,
            EpisodeCursor cursor,
            IncidentDrillRunner drills,
            @ConfigProperty(name = "ajentic.mcp.default-scenario", defaultValue = "checkout-latency-db-pool")
            String defaultScenarioId) {
        this.scenarios = scenarios;
        this.channel = channel;
        this.episodes = episodes;
        this.cursor = cursor;
        this.drills = drills;
        this.defaultScenarioId = defaultScenarioId;
    }

    @AgentTool(name = "query_telemetry", description = "Fetch correlated telemetry for a service.")
    public ToolResult queryTelemetry(
            @ToolParam("Telemetry signal to fetch.") TelemetrySignal signal,
            @ToolParam("Service name to inspect.") String service,
            ToolContext context) {
        StepResult result = context.environment().step(new QueryTelemetry(signal, service));
        List<ObservedSignal> signals = result.observation().signals();
        if (signals.isEmpty()) {
            return ToolResult.ok("No " + signal + " samples for " + service + ".");
        }
        return ToolResult.ok(ToolText.format(signals), Map.of("signal", signal.name(), "count", signals.size()));
    }

    @AgentTool(name = "read_runbook", description = "Read an operational runbook that matches the incident.")
    public ToolResult readRunbook(
            @ToolParam("Runbook identifier from the observation.") String runbookId,
            ToolContext context) {
        context.environment().step(new ReadRunbook(runbookId));
        String scenarioId = context.environment().spec().scenarioId();
        Scenario scenario = scenarios.byId(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + scenarioId));
        Runbook runbook = scenario.runbooks().stream()
                .filter(candidate -> candidate.id().equals(runbookId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown runbook: " + runbookId));
        return ToolResult.ok(runbook.title() + "\n" + runbook.body());
    }

    @AgentTool(name = "list_remediations", description = "List the remediation actions that can be applied.")
    public ToolResult listRemediations(ToolContext context) {
        StepResult result = context.environment().step(new ListRemediations());
        List<RemediationKind> remediations = result.observation().availableRemediations();
        String content = remediations.stream()
                .map(kind -> "- %s: %s".formatted(kind, kind.description()))
                .collect(Collectors.joining("\n"));
        return ToolResult.ok(content, Map.of("count", remediations.size()));
    }

    @AgentTool(name = "apply_remediation", description = "Apply a remediation action to a service.",
            capability = Capability.MUTATE)
    public ToolResult applyRemediation(
            @ToolParam("Remediation to apply.") RemediationKind remediation,
            @ToolParam("Service to remediate.") String service,
            ToolContext context) {
        StepResult result = context.environment().step(new ApplyRemediation(remediation, service));
        return ToolResult.ok(result.observation().feedback(), Map.of(
                "reward", result.reward(),
                "terminal", result.done()));
    }

    @AgentTool(name = "declare_diagnosis", description = "Record the identified root cause for the incident.",
            capability = Capability.MUTATE)
    public ToolResult declareDiagnosis(
            @ToolParam("Service that owns the root cause.") String service,
            @ToolParam("Concise root cause statement.") String rootCause,
            ToolContext context) {
        StepResult result = context.environment().step(new DeclareDiagnosis(service, rootCause));
        return ToolResult.ok(result.observation().feedback(), Map.of(
                "reward", result.reward(),
                "terminal", result.done()));
    }

    @AgentTool(name = "post_incident_summary",
            description = "Post an incident summary to the configured notification channel.",
            capability = Capability.MUTATE)
    public ToolResult postIncidentSummary(
            @ToolParam("Summary to post.") String summary,
            @ToolParam(value = "Incident status, for example resolved.", required = false, defaultValue = "investigating")
            String status,
            ToolContext context) {
        channel.post(new IncidentSummary(context.sessionId(), summary, status, List.of()));
        return ToolResult.ok("Incident summary posted with status '" + status + "'.");
    }

    @AgentTool(name = "list_scenarios", description = "List the incident scenarios available on this server.")
    public ToolResult listScenarios() {
        List<Scenario> all = scenarios.all();
        String text = all.stream()
                .map(scenario -> "- %s: %s".formatted(scenario.id(), scenario.title()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("No scenarios available.");
        return ToolResult.ok(text, Map.of("count", all.size()));
    }

    @AgentTool(name = "start_episode",
            description = "Start an isolated episode for a scenario and make it the active episode.",
            capability = Capability.MUTATE)
    public ToolResult startEpisode(
            @ToolParam(value = "Scenario to start; defaults to the configured scenario.", required = false)
            String scenarioId,
            @ToolParam(value = "Deterministic seed; defaults to 42.", required = false, defaultValue = "42")
            Long seed) {
        String target = scenarioId == null || scenarioId.isBlank() ? defaultScenarioId : scenarioId;
        EpisodeHandle handle = episodes.start(target, seed);
        cursor.activate(handle.id());
        return ToolResult.ok(
                "Started episode %s for scenario %s.".formatted(handle.id(), target),
                Map.of("episodeId", handle.id(), "scenarioId", target));
    }

    @AgentTool(name = "run_incident_drill",
            description = "Run the heuristic policy end to end against a scenario and report the result.",
            capability = Capability.MUTATE)
    public ToolResult runIncidentDrill(@ToolParam("Scenario to drill.") String scenarioId) {
        EpisodeResult result = drills.drill(scenarioId);
        return ToolResult.ok(
                "Drill for %s finished: solved=%s diagnosed=%s resolved=%s reward=%.2f."
                        .formatted(scenarioId, result.solved(), result.diagnosed(), result.resolved(),
                                result.totalReward()),
                Map.of(
                        "scenarioId", scenarioId,
                        "solved", result.solved(),
                        "diagnosed", result.diagnosed(),
                        "resolved", result.resolved(),
                        "totalReward", result.totalReward(),
                        "steps", result.steps()));
    }
}
