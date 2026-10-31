package io.ajentic.sre.agent.tool.builtin;

import io.ajentic.sre.agent.context.AgentExecutionContext;
import io.ajentic.sre.agent.integration.IncidentSummary;
import io.ajentic.sre.agent.integration.NotificationChannel;
import io.ajentic.sre.agent.memory.IncidentMemoryStore;
import io.ajentic.sre.agent.memory.StoredIncident;
import io.ajentic.sre.agent.tool.ToolBox;
import io.ajentic.sre.agent.tool.ToolContext;
import io.ajentic.sre.simulation.ObservedSignal;
import io.ajentic.sre.simulation.StepResult;
import io.ajentic.sre.simulation.TelemetrySignal;
import io.ajentic.sre.simulation.action.ApplyRemediation;
import io.ajentic.sre.simulation.action.CorrelateAnomalies;
import io.ajentic.sre.simulation.action.DeclareDiagnosis;
import io.ajentic.sre.simulation.action.InspectRecentChanges;
import io.ajentic.sre.simulation.action.ListRemediations;
import io.ajentic.sre.simulation.action.QueryTelemetry;
import io.ajentic.sre.simulation.action.ReadRunbook;
import io.ajentic.sre.simulation.action.RemediationKind;
import io.ajentic.sre.simulation.drill.SimulationCursor;
import io.ajentic.sre.simulation.drill.SimulationHandle;
import io.ajentic.sre.simulation.drill.SimulationResult;
import io.ajentic.sre.simulation.drill.SimulationService;
import io.ajentic.sre.simulation.Runbook;
import io.ajentic.sre.simulation.Scenario;
import io.ajentic.sre.simulation.ScenarioRepository;
import io.ajentic.sre.schedule.drill.IncidentDrillRunner;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Built-in SRE capabilities declared as declarative Quarkus MCP {@link Tool} methods.
 */
@ToolBox
@Unremovable
public class BuiltInSreTools {

    private final ScenarioRepository scenarios;
    private final NotificationChannel channel;
    private final SimulationService episodes;
    private final SimulationCursor cursor;
    private final IncidentDrillRunner drills;
    private final IncidentMemoryStore memory;
    private final String defaultScenarioId;

    @Inject
    public BuiltInSreTools(
            ScenarioRepository scenarios,
            NotificationChannel channel,
            SimulationService episodes,
            SimulationCursor cursor,
            IncidentDrillRunner drills,
            IncidentMemoryStore memory,
            @ConfigProperty(name = "ajentic.mcp.default-scenario", defaultValue = "checkout-latency-db-pool")
            String defaultScenarioId) {
        this.scenarios = scenarios;
        this.channel = channel;
        this.episodes = episodes;
        this.cursor = cursor;
        this.drills = drills;
        this.memory = memory;
        this.defaultScenarioId = defaultScenarioId;
    }

    @Tool(name = "query_telemetry", description = "Fetch correlated telemetry for a service.")
    public ToolResponse queryTelemetry(
            @ToolArg(name = "signal", description = "Telemetry signal: METRICS, LOGS, TRACES, DEPLOYS") TelemetrySignal signal,
            @ToolArg(name = "service", description = "Service name to inspect") String service,
            McpConnection connection) {
        try {
            ToolContext context = resolveContext(connection);
            StepResult result = context.environment().step(new QueryTelemetry(signal, service));
            List<ObservedSignal> signals = result.observation().signals();
            if (signals.isEmpty()) {
                return ToolResponse.success("No " + signal + " samples for " + service + ".");
            }
            return ToolResponse.success(ToolText.format(signals));
        } catch (RuntimeException exception) {
            return ToolResponse.error(errorMessage(exception));
        }
    }

    @Tool(name = "inspect_recent_changes", description = "List recent deploys, config edits, flag flips and quota changes for a service.")
    public ToolResponse inspectRecentChanges(
            @ToolArg(name = "service", description = "Service name to inspect.") String service,
            @ToolArg(name = "window", description = "Lookback window in minutes. Default 1440.", required = false) Integer window,
            McpConnection connection) {
        try {
            ToolContext context = resolveContext(connection);
            StepResult result = context.environment().step(new InspectRecentChanges(service, window == null ? 1440 : window));
            List<ObservedSignal> signals = result.observation().signals();
            if (signals.isEmpty()) {
                return ToolResponse.success("No recent changes for " + service + ".");
            }
            return ToolResponse.success(ToolText.format(signals));
        } catch (RuntimeException exception) {
            return ToolResponse.error(errorMessage(exception));
        }
    }

    @Tool(name = "correlate_anomalies", description = "Rank metric anomalies across a service's topology neighborhood.")
    public ToolResponse correlateAnomalies(
            @ToolArg(name = "services", description = "Service names to correlate; empty uses the alerting service.", required = false) List<String> services,
            @ToolArg(name = "window", description = "Neighborhood radius in hops. Default 2.", required = false) Integer window,
            McpConnection connection) {
        try {
            ToolContext context = resolveContext(connection);
            StepResult result = context.environment().step(
                    new CorrelateAnomalies(services == null ? List.of() : services, window == null ? 2 : window));
            return ToolResponse.success(result.observation().feedback());
        } catch (RuntimeException exception) {
            return ToolResponse.error(errorMessage(exception));
        }
    }

    @Tool(name = "read_runbook", description = "Read an operational runbook that matches the incident.")
    public ToolResponse readRunbook(
            @ToolArg(name = "runbookId", description = "Runbook identifier from the observation.") String runbookId,
            McpConnection connection) {
        try {
            ToolContext context = resolveContext(connection);
            context.environment().step(new ReadRunbook(runbookId));
            String scenarioId = context.environment().spec().scenarioId();
            Scenario scenario = scenarios.byId(scenarioId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + scenarioId));
            Runbook runbook = scenario.runbooks().stream()
                    .filter(candidate -> candidate.id().equals(runbookId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown runbook: " + runbookId));
            return ToolResponse.success(runbook.title() + "\n" + runbook.body());
        } catch (RuntimeException exception) {
            return ToolResponse.error(errorMessage(exception));
        }
    }

    @Tool(name = "list_remediations", description = "List the remediation actions that can be applied.")
    public ToolResponse listRemediations(McpConnection connection) {
        try {
            ToolContext context = resolveContext(connection);
            StepResult result = context.environment().step(new ListRemediations());
            List<RemediationKind> remediations = result.observation().availableRemediations();
            String content = remediations.stream()
                    .map(kind -> "- %s: %s".formatted(kind, kind.description()))
                    .collect(Collectors.joining("\n"));
            return ToolResponse.success(content);
        } catch (RuntimeException exception) {
            return ToolResponse.error(errorMessage(exception));
        }
    }

    @Tool(name = "apply_remediation", description = "Apply a remediation action to a service.")
    public ToolResponse applyRemediation(
            @ToolArg(name = "remediation", description = "Remediation to apply.") RemediationKind remediation,
            @ToolArg(name = "service", description = "Service to remediate.") String service,
            McpConnection connection) {
        try {
            ToolContext context = resolveContext(connection);
            StepResult result = context.environment().step(new ApplyRemediation(remediation, service));
            return ToolResponse.success(result.observation().feedback());
        } catch (RuntimeException exception) {
            return ToolResponse.error(errorMessage(exception));
        }
    }

    @Tool(name = "declare_diagnosis", description = "Record the identified root cause for the incident.")
    public ToolResponse declareDiagnosis(
            @ToolArg(name = "service", description = "Service that owns the root cause.") String service,
            @ToolArg(name = "rootCause", description = "Concise root cause statement.") String rootCause,
            McpConnection connection) {
        try {
            ToolContext context = resolveContext(connection);
            StepResult result = context.environment().step(new DeclareDiagnosis(service, rootCause));
            return ToolResponse.success(result.observation().feedback());
        } catch (RuntimeException exception) {
            return ToolResponse.error(errorMessage(exception));
        }
    }

    @Tool(name = "search_past_incidents", description = "Search previously resolved incidents by symptom text, service, and fault kind.")
    public ToolResponse searchPastIncidents(
            @ToolArg(name = "query", description = "Symptom text to search for (blank = any).", required = false) String query,
            @ToolArg(name = "service", description = "Service name (blank = any).", required = false) String service,
            @ToolArg(name = "faultKind", description = "Fault kind, e.g. DB_POOL_EXHAUSTION (blank = any).", required = false) String faultKind,
            @ToolArg(name = "limit", description = "Maximum number of results.", required = false) Integer limit) {
        int max = limit == null || limit < 1 ? 3 : limit;
        List<StoredIncident> matches = memory.search(query, service, faultKind, max);
        if (matches.isEmpty()) {
            return ToolResponse.success("No past incidents match.");
        }
        String content = matches.stream()
                .map(incident -> "- %s [%s/%s] solved=%s reward=%.2f%s%s".formatted(
                        incident.scenarioId(),
                        incident.service(),
                        incident.faultKind(),
                        incident.solved(),
                        incident.reward(),
                        incident.diagnosis().isBlank() ? "" : " | diagnosis: " + incident.diagnosis(),
                        incident.remediation().isBlank() ? "" : " | remediation: " + incident.remediation()))
                .collect(Collectors.joining("\n"));
        return ToolResponse.success(content);
    }

    @Tool(name = "get_service_topology", description = "Show the service dependency graph for the active scenario.")
    public ToolResponse getServiceTopology(McpConnection connection) {
        try {
            ToolContext context = resolveContext(connection);
            String scenarioId = context.environment().spec().scenarioId();
            Scenario scenario = scenarios.byId(scenarioId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + scenarioId));
            String text = scenario.services().stream()
                    .map(spec -> spec.name() + (spec.dependencies().isEmpty()
                            ? " -> (no dependencies)"
                            : " -> " + String.join(", ", spec.dependencies())))
                    .collect(Collectors.joining("\n"));
            return ToolResponse.success(text);
        } catch (RuntimeException exception) {
            return ToolResponse.error(errorMessage(exception));
        }
    }

    @Tool(name = "post_incident_summary", description = "Post an incident summary to the configured notification channel.")
    public ToolResponse postIncidentSummary(
            @ToolArg(name = "summary", description = "Summary to post.") String summary,
            @ToolArg(name = "status", description = "Incident status, for example resolved.", required = false, defaultValue = "investigating") String status,
            McpConnection connection) {
        try {
            ToolContext context = resolveContext(connection);
            channel.post(new IncidentSummary(context.sessionId(), summary, status, List.of()));
            return ToolResponse.success("Incident summary posted with status '" + status + "'.");
        } catch (RuntimeException exception) {
            return ToolResponse.error(errorMessage(exception));
        }
    }

    @Tool(name = "list_scenarios", description = "List the incident scenarios available on this server.")
    public ToolResponse listScenarios() {
        List<Scenario> all = scenarios.all();
        String text = all.stream()
                .map(scenario -> "- %s: %s".formatted(scenario.id(), scenario.title()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("No scenarios available.");
        return ToolResponse.success(text);
    }

    @Tool(name = "start_episode", description = "Start an isolated episode for a scenario and make it the active episode.")
    public ToolResponse startEpisode(
            @ToolArg(name = "scenarioId", description = "Scenario to start; defaults to the configured scenario.", required = false) String scenarioId,
            @ToolArg(name = "seed", description = "Deterministic seed; defaults to 42.", required = false) Long seed,
            McpConnection connection) {
        String target = scenarioId == null || scenarioId.isBlank() ? defaultScenarioId : scenarioId;
        long effectiveSeed = seed == null ? 42L : seed;
        SimulationHandle handle = episodes.start(target, effectiveSeed);
        String scope = connection != null ? connection.id() : AgentExecutionContext.mcpScope();
        if (scope == null || scope.isBlank()) {
            scope = AgentExecutionContext.sessionId() != null ? AgentExecutionContext.sessionId() : SimulationCursor.DEFAULT_SCOPE;
        }
        cursor.activate(scope, handle.id());
        return ToolResponse.success("Started episode " + handle.id() + " for scenario " + target + ". episodeId: " + handle.id());
    }

    @Tool(name = "start_simulation", description = "Start an isolated simulation for a scenario and make it the active simulation.")
    public ToolResponse startSimulation(
            @ToolArg(name = "scenarioId", description = "Scenario to start; defaults to the configured scenario.", required = false) String scenarioId,
            @ToolArg(name = "seed", description = "Deterministic seed; defaults to 42.", required = false) Long seed,
            McpConnection connection) {
        return startEpisode(scenarioId, seed, connection);
    }

    @Tool(name = "run_incident_drill", description = "Run the heuristic policy end to end against a scenario and report the result.")
    public ToolResponse runIncidentDrill(
            @ToolArg(name = "scenarioId", description = "Scenario to drill.") String scenarioId) {
        try {
            SimulationResult result = drills.drill(scenarioId);
            return ToolResponse.success("Drill for %s finished: solved=%s diagnosed=%s resolved=%s reward=%.2f. {\"solved\":%s}"
                    .formatted(scenarioId, result.solved(), result.diagnosed(), result.resolved(), result.totalReward(), result.solved()));
        } catch (RuntimeException exception) {
            return ToolResponse.error(errorMessage(exception));
        }
    }

    private ToolContext resolveContext(McpConnection connection) {
        ToolContext bound = AgentExecutionContext.toolContext();
        if (bound != null) {
            return bound;
        }
        String scope = connection != null ? connection.id() : AgentExecutionContext.mcpScope();
        if (scope == null || scope.isBlank()) {
            scope = AgentExecutionContext.sessionId() != null ? AgentExecutionContext.sessionId() : SimulationCursor.DEFAULT_SCOPE;
        }
        String activeId = cursor.activeId(scope);
        if (activeId != null) {
            Optional<SimulationHandle> active = episodes.find(activeId);
            if (active.isPresent()) {
                return new ToolContext(active.get().id(), active.get().environment());
            }
        }
        SimulationHandle handle = episodes.start(defaultScenarioId, 42L);
        cursor.activate(scope, handle.id());
        return new ToolContext(handle.id(), handle.environment());
    }

    private static String errorMessage(RuntimeException exception) {
        return exception.getMessage() != null && !exception.getMessage().isBlank()
                ? exception.getMessage()
                : exception.getClass().getSimpleName();
    }
}
