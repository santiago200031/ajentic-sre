package io.ajentic.sre.rl.environment.cloud;

import io.ajentic.sre.agent.cloud.CloudApiException;
import io.ajentic.sre.agent.cloud.CloudHttpClient;
import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.EnvironmentSpec;
import io.ajentic.sre.rl.Observation;
import io.ajentic.sre.rl.ObservedSignal;
import io.ajentic.sre.rl.ResetRequest;
import io.ajentic.sre.rl.ServiceHealth;
import io.ajentic.sre.rl.StepResult;
import io.ajentic.sre.rl.TelemetrySignal;
import io.ajentic.sre.rl.TerminationReason;
import io.ajentic.sre.rl.action.AgentAction;
import io.ajentic.sre.rl.action.ApplyRemediation;
import io.ajentic.sre.rl.action.CorrelateAnomalies;
import io.ajentic.sre.rl.action.DeclareDiagnosis;
import io.ajentic.sre.rl.action.Idle;
import io.ajentic.sre.rl.action.InspectRecentChanges;
import io.ajentic.sre.rl.action.ListRemediations;
import io.ajentic.sre.rl.action.QueryTelemetry;
import io.ajentic.sre.rl.action.ReadRunbook;
import io.ajentic.sre.rl.action.RemediationKind;
import io.ajentic.sre.rl.reward.RewardFunction;
import io.ajentic.sre.rl.reward.Transition;
import io.ajentic.sre.rl.reward.TransitionEffect;
import io.ajentic.sre.rl.simulation.DeployRecord;
import io.ajentic.sre.rl.simulation.Runbook;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ServiceSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Base environment for real cloud provider adapters (AWS, Azure, GCP).
 * Provides unified step execution, circuit-breaking, mutation gating, and telemetry normalization.
 */
public abstract class AbstractCloudEnvironment implements Environment {

    protected final Scenario scenario;
    protected final RewardFunction rewardFunction;
    protected final CloudHttpClient httpClient;
    protected final boolean allowMutations;
    protected final boolean readOnly;

    protected final Map<String, ServiceMetrics> metrics = new LinkedHashMap<>();

    private Observation observation;
    private int step;
    private double cumulativeReward;
    private boolean diagnosed;
    private boolean terminal;
    private TerminationReason reason = TerminationReason.NONE;

    protected AbstractCloudEnvironment(
            Scenario scenario,
            RewardFunction rewardFunction,
            CloudHttpClient httpClient,
            boolean allowMutations,
            boolean readOnly) {
        this.scenario = scenario;
        this.rewardFunction = rewardFunction;
        this.httpClient = httpClient != null ? httpClient : new CloudHttpClient();
        this.allowMutations = allowMutations;
        this.readOnly = readOnly;
        reset(new ResetRequest(scenario.id(), 42));
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
    public synchronized Observation reset(ResetRequest request) {
        step = 0;
        cumulativeReward = 0.0;
        diagnosed = false;
        terminal = false;
        reason = TerminationReason.NONE;
        httpClient.resetTurnBudget();
        refresh();
        observation = buildObservation(
                "Cloud incident alert: " + scenario.alertingService() + " reported degraded performance.",
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
        httpClient.resetTurnBudget();

        EnumSet<TransitionEffect> effects = EnumSet.noneOf(TransitionEffect.class);
        List<ObservedSignal> signals = List.of();
        String feedback;

        try {
            switch (action) {
                case QueryTelemetry query -> {
                    signals = signals(query.signal(), query.service());
                    feedback = "Returned %d %s sample(s) for %s from cloud provider."
                            .formatted(signals.size(), query.signal(), query.service());
                }
                case InspectRecentChanges changes -> {
                    List<DeployRecord> records = fetchRecentChanges(changes.service(), changes.windowMinutes());
                    signals = records.stream()
                            .map(r -> new ObservedSignal(TelemetrySignal.DEPLOYS, r.service(),
                                    "[" + r.kind() + "] " + r.summary() + (r.diff().isEmpty() ? "" : "\n" + r.diff()),
                                    Map.of("kind", r.kind(), "occurredAt", r.occurredAt().toString())))
                            .toList();
                    feedback = records.isEmpty()
                            ? "No recent cloud changes found for " + changes.service() + "."
                            : "Found " + records.size() + " recent cloud changes for " + changes.service() + ".";
                }
                case CorrelateAnomalies anomalies -> {
                    List<String> targetServices = (anomalies.services() != null && !anomalies.services().isEmpty())
                            ? anomalies.services()
                            : scenario.services().stream().map(ServiceSpec::name).toList();
                    List<String> ranked = rankAnomalies(targetServices);
                    feedback = "Anomaly ranking across services: " + String.join(" > ", ranked);
                }
                case ReadRunbook runbook -> feedback = "Opened runbook " + runbook.runbookId() + ".";
                case ListRemediations ignored -> feedback = "Listed available cloud remediations.";
                case ApplyRemediation remediation -> {
                    if (readOnly) {
                        feedback = "Shadow mode: remediation " + remediation.remediation() + " on "
                                + remediation.service() + " was not executed on the live cloud system.";
                    } else if (!allowMutations) {
                        feedback = "Cloud mutation rejected: mutations are disabled in provider configuration.";
                    } else {
                        feedback = executeRemediation(remediation.service(), remediation.remediation());
                        effects.add(TransitionEffect.REMEDIATION_CORRECT);
                    }
                }
                case DeclareDiagnosis diagnosis -> {
                    diagnosed = true;
                    effects.add(TransitionEffect.DIAGNOSIS_CORRECT);
                    feedback = "Cloud incident diagnosis recorded for " + diagnosis.service() + ".";
                }
                case Idle ignored -> feedback = "No action taken.";
            }
        } catch (CloudApiException e) {
            feedback = "Cloud provider API error: " + e.getMessage();
        } catch (Exception e) {
            feedback = "Cloud adapter error: " + e.getMessage();
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
    public synchronized boolean isTerminal() {
        return terminal;
    }

    @Override
    public synchronized boolean isDiagnosed() {
        return diagnosed;
    }

    @Override
    public synchronized boolean isResolved() {
        return !metrics.isEmpty() && metrics.values().stream()
                .allMatch(m -> m.errorRate() < 0.05 && m.p95LatencyMs() < 400.0);
    }

    protected void refresh() {
        for (ServiceSpec spec : scenario.services()) {
            try {
                ServiceMetrics m = fetchMetrics(spec.name());
                if (m != null) {
                    metrics.put(spec.name(), m);
                }
            } catch (Exception ignored) {
                // keep the last known reading; failures surface when actively queried
            }
        }
    }

    protected List<String> rankAnomalies(List<String> targetServices) {
        List<Map.Entry<String, ServiceMetrics>> entries = new ArrayList<>();
        for (String svc : targetServices) {
            ServiceMetrics sm = metrics.get(svc);
            if (sm != null) {
                entries.add(Map.entry(svc, sm));
            }
        }
        entries.sort((a, b) -> Double.compare(
                b.getValue().errorRate() * 1000 + b.getValue().p95LatencyMs(),
                a.getValue().errorRate() * 1000 + a.getValue().p95LatencyMs()));
        return entries.stream().map(Map.Entry::getKey).toList();
    }

    private Observation buildObservation(String feedback, List<ObservedSignal> signals) {
        Map<String, ServiceHealth> healthMap = new LinkedHashMap<>();
        for (Map.Entry<String, ServiceMetrics> entry : metrics.entrySet()) {
            ServiceMetrics m = entry.getValue();
            if (m.errorRate() > 0.25 || m.p95LatencyMs() > 1500) {
                healthMap.put(entry.getKey(), ServiceHealth.OUTAGE);
            } else if (m.errorRate() > 0.05 || m.p95LatencyMs() > 400) {
                healthMap.put(entry.getKey(), ServiceHealth.DEGRADED);
            } else {
                healthMap.put(entry.getKey(), ServiceHealth.HEALTHY);
            }
        }

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
                healthMap,
                cumulativeReward);
    }

    /** Fetch errorRate and p95LatencyMs for a service. */
    protected abstract ServiceMetrics fetchMetrics(String service);

    /** Fetch recent change/deploy events for a service within lookback window. */
    protected abstract List<DeployRecord> fetchRecentChanges(String service, int windowMinutes);

    /** Execute a remediation action against the live cloud service. */
    protected abstract String executeRemediation(String service, RemediationKind kind);

    public record ServiceMetrics(double errorRate, double p95LatencyMs) {
    }
}
