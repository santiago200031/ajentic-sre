package io.ajentic.sre.simulation.environment;

import io.ajentic.sre.simulation.Environment;
import io.ajentic.sre.simulation.EnvironmentSpec;
import io.ajentic.sre.simulation.Observation;
import io.ajentic.sre.simulation.ObservedSignal;
import io.ajentic.sre.simulation.ServiceHealth;
import io.ajentic.sre.simulation.StepResult;
import io.ajentic.sre.simulation.TelemetrySignal;
import io.ajentic.sre.simulation.TerminationReason;
import io.ajentic.sre.simulation.action.AgentAction;
import io.ajentic.sre.simulation.action.ApplyRemediation;
import io.ajentic.sre.simulation.action.CorrelateAnomalies;
import io.ajentic.sre.simulation.action.DeclareDiagnosis;
import io.ajentic.sre.simulation.action.Idle;
import io.ajentic.sre.simulation.action.InspectRecentChanges;
import io.ajentic.sre.simulation.action.ListRemediations;
import io.ajentic.sre.simulation.action.QueryTelemetry;
import io.ajentic.sre.simulation.action.ReadRunbook;
import io.ajentic.sre.simulation.action.RemediationKind;
import io.ajentic.sre.simulation.scorecard.Scorecard;
import io.ajentic.sre.simulation.scorecard.Transition;
import io.ajentic.sre.simulation.scorecard.TransitionEffect;
import io.ajentic.sre.simulation.Runbook;
import io.ajentic.sre.simulation.Scenario;
import io.ajentic.sre.simulation.ServiceSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Base for the live telemetry backends. The scenario supplies the service topology, runbooks and step
 * budget; live state comes from Prometheus-compatible metrics and Loki-compatible logs over HTTP.
 *
 * <p>{@code readOnly} backends never execute remediations: {@code apply_remediation} returns a shadow-mode
 * result instead of touching the live system, regardless of {@code ajentic.tools.allow-mutations}.
 */
public class LiveTelemetryEnvironment implements Environment {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Scenario scenario;
    private final LiveTelemetryConfig config;
    private final Scorecard rewardFunction;
    private final LiveHttp http;
    private final boolean readOnly;

    private final Map<String, LiveMetrics> metrics = new LinkedHashMap<>();

    private Observation observation;
    private int step;
    private double cumulativeReward;
    private boolean diagnosed;
    private boolean terminal;
    private TerminationReason reason = TerminationReason.NONE;

    protected LiveTelemetryEnvironment(
            Scenario scenario,
            LiveTelemetryConfig config,
            Scorecard rewardFunction,
            boolean readOnly) {
        this.scenario = scenario;
        this.config = config;
        this.rewardFunction = rewardFunction;
        this.http = new LiveHttp(config.timeout(), 2);
        this.readOnly = readOnly;
        reset(new io.ajentic.sre.simulation.ResetRequest(scenario.id(), 42));
    }

    @Override
    public EnvironmentSpec spec() {
        return new EnvironmentSpec(
                scenario.id(),
                scenario.title(),
                scenario.summary(),
                scenario.maxSteps(),
                Set.of(TelemetrySignal.values()),
                scenario.runbooks().stream().map(Runbook::id).toList(),
                List.of(RemediationKind.values()));
    }

    @Override
    public synchronized Observation reset(io.ajentic.sre.simulation.ResetRequest request) {
        step = 0;
        cumulativeReward = 0.0;
        diagnosed = false;
        terminal = false;
        reason = TerminationReason.NONE;
        refresh();
        observation = buildObservation(
                "Alert: " + scenario.alertingService() + " reports a downstream error spike.",
                List.of());
        return observation;
    }

    @Override
    public synchronized StepResult step(AgentAction action) {
        if (terminal) {
            return new StepResult(observation, 0.0, reason);
        }

        Observation before = observation;
        step++;

        EnumSet<TransitionEffect> effects = EnumSet.noneOf(TransitionEffect.class);
        List<ObservedSignal> signals = List.of();
        String feedback;
        try {
            switch (action) {
                case QueryTelemetry query -> {
                    signals = signals(query.signal(), query.service());
                    feedback = "Returned %d %s sample(s) for %s."
                            .formatted(signals.size(), query.signal(), query.service());
                }
                case InspectRecentChanges ignored -> feedback = "Recent changes are not available in live backends.";
                case CorrelateAnomalies ignored -> feedback = "Anomaly correlation is not available in live backends.";
                case ReadRunbook runbook -> feedback = "Opened runbook " + runbook.runbookId() + ".";
                case ListRemediations ignored -> feedback = "Listed the available remediations.";
                case ApplyRemediation remediation -> {
                    if (readOnly) {
                        feedback = "Shadow mode: remediation " + remediation.remediation() + " for "
                                + remediation.service() + " was not executed on the live system.";
                    } else if (config.remediationUrl() == null || config.remediationUrl().isBlank()) {
                        feedback = "Remediation " + remediation.remediation() + " was not dispatched: "
                                + "no remediation endpoint configured.";
                    } else {
                        http.post(config.remediationUrl(),
                                "{\"service\":\"" + remediation.service() + "\",\"remediation\":\""
                                        + remediation.remediation() + "\"}");
                        effects.add(TransitionEffect.REMEDIATION_CORRECT);
                        feedback = "Remediation " + remediation.remediation() + " dispatched to "
                                + remediation.service() + ".";
                    }
                }
                case DeclareDiagnosis diagnosis -> {
                    diagnosed = true;
                    effects.add(TransitionEffect.DIAGNOSIS_CORRECT);
                    feedback = "Diagnosis recorded for " + diagnosis.service() + ".";
                }
                case Idle ignored -> feedback = "No action taken.";
            }
        } catch (LiveHttpException e) {
            feedback = "Telemetry backend error: " + e.getMessage();
        }

        refresh();

        if (diagnosed && isResolved()) {
            terminal = true;
            reason = TerminationReason.RESOLVED;
            effects.add(TransitionEffect.RESOLVED);
        } else if (step >= scenario.maxSteps()) {
            terminal = true;
            reason = TerminationReason.MAX_STEPS;
        }

        Observation after = buildObservation(feedback, signals);
        double reward = rewardFunction.reward(new Transition(action, before, after, effects));
        cumulativeReward += reward;
        observation = after.withCumulativeReward(cumulativeReward);
        return new StepResult(observation, reward, reason);
    }

    @Override
    public synchronized Observation observe() {
        return observation;
    }

    @Override
    public synchronized List<ObservedSignal> signals(TelemetrySignal signal, String service) {
        return switch (signal) {
            case METRICS -> {
                double errorRate = prometheusLastValue(service, errorRateQuery(service));
                double latency = prometheusLastValue(service, p95LatencyQuery(service));
                yield List.of(new ObservedSignal(
                        signal,
                        service,
                        "errorRate=%.4f p95LatencyMs=%.1f".formatted(errorRate, latency),
                        Map.of("errorRate", errorRate, "p95LatencyMs", latency)));
            }
            case LOGS -> {
                List<String> lines = lokiLines(service);
                List<ObservedSignal> result = new ArrayList<>(lines.size());
                for (String line : lines) {
                    result.add(new ObservedSignal(signal, service, line, Map.of()));
                }
                if (result.isEmpty()) {
                    result.add(new ObservedSignal(signal, service, "No log lines in the window.", Map.of()));
                }
                yield result;
            }
            case TRACES, DEPLOYS -> List.of(new ObservedSignal(signal, service, "Not available in live backends.", Map.of()));
        };
    }

    @Override
    public synchronized boolean isTerminal() {
        return terminal;
    }

    @Override
    public synchronized boolean isDiagnosed() {
        return diagnosed;
    }

    @Override
    public synchronized boolean isResolved() {
        return !metrics.isEmpty()
                && metrics.values().stream()
                .allMatch(m -> m.errorRate() < 0.05 && m.p95LatencyMilliseconds() < 400.0);
    }

    private void refresh() {
        for (ServiceSpec spec : scenario.services()) {
            try {
                double errorRate = prometheusLastValue(spec.name(), errorRateQuery(spec.name()));
                double latency = prometheusLastValue(spec.name(), p95LatencyQuery(spec.name()));
                metrics.put(spec.name(), new LiveMetrics(errorRate, latency));
            } catch (LiveHttpException ignored) {
                // keep the last healthy reading; upstream failures surface through signals/step feedback
            }
        }
    }

    private double prometheusLastValue(String service, String query) {
        String body = http.get(queryRangeUrl(config.prometheusUrl(), query));
        JsonNode values = readTree(body).path("data").path("result").path(0).path("values");
        if (!values.isArray() || values.isEmpty()) {
            return 0.0;
        }
        return values.get(values.size() - 1).path(1).asDouble();
    }

    private List<String> lokiLines(String service) {
        String body = http.get(queryRangeUrl(config.lokiUrl(), "{service=\"" + service + "\"}"));
        JsonNode streams = readTree(body).path("data").path("result");
        List<String> lines = new ArrayList<>();
        for (JsonNode stream : streams) {
            for (JsonNode value : stream.path("values")) {
                lines.add(value.path(1).asText());
            }
        }
        return lines;
    }

    private static JsonNode readTree(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse telemetry JSON response: " + e.getMessage(), e);
        }
    }

    private static String queryRangeUrl(String baseUrl, String query) {
        Instant end = Instant.now();
        Instant start = end.minus(15, ChronoUnit.MINUTES);
        return baseUrl + "/api/v1/query_range?query=" + encode(query)
                + "&start=" + (start.toEpochMilli() / 1000.0)
                + "&end=" + (end.toEpochMilli() / 1000.0)
                + "&step=60&limit=20";
    }

    private static String errorRateQuery(String service) {
        return "sum(rate(http_requests_total{service=\"" + service + "\",status=~\"5..\"}[5m]))"
                + " / sum(rate(http_requests_total{service=\"" + service + "\"}[5m]))";
    }

    private static String p95LatencyQuery(String service) {
        return "histogram_quantile(0.95, sum(rate(http_request_duration_seconds_bucket{service=\""
                + service + "\"}[5m])) by (le)) * 1000";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Observation buildObservation(String feedback, List<ObservedSignal> signals) {
        return new Observation(
                scenario.id(),
                scenario.incidentSummary(),
                step,
                scenario.maxSteps(),
                terminal,
                isResolved(),
                diagnosed,
                feedback,
                signals,
                scenario.runbooks().stream().map(Runbook::id).toList(),
                List.of(RemediationKind.values()),
                health(),
                cumulativeReward);
    }

    private Map<String, ServiceHealth> health() {
        Map<String, ServiceHealth> health = new LinkedHashMap<>();
        metrics.forEach((service, m) -> health.put(service, healthFor(m)));
        return health;
    }

    private static ServiceHealth healthFor(LiveMetrics m) {
        if (m.errorRate() > 0.25 || m.p95LatencyMilliseconds() > 1500) {
            return ServiceHealth.OUTAGE;
        }
        if (m.errorRate() > 0.05 || m.p95LatencyMilliseconds() > 400) {
            return ServiceHealth.DEGRADED;
        }
        return ServiceHealth.HEALTHY;
    }

    /** Last observed live readings for one service. */
    public record LiveMetrics(double errorRate, double p95LatencyMilliseconds) {
    }
}
