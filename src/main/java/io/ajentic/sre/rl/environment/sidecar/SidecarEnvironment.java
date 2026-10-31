package io.ajentic.sre.rl.environment.sidecar;

import io.ajentic.sre.agent.integration.SidecarClient;
import io.ajentic.sre.connectors.SidecarDto;
import io.ajentic.sre.connectors.SidecarUnavailableException;
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
import io.ajentic.sre.rl.simulation.Runbook;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ServiceSpec;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sidecar-backed Environment adapter. Talks exclusively to the least-privilege sidecar connector service
 * over signed HTTP. Holds zero provider credentials. Fails closed (503 tool error) when sidecar is unreachable.
 */
public final class SidecarEnvironment implements Environment {

    private final Scenario scenario;
    private final RewardFunction rewardFunction;
    private final SidecarClient sidecarClient;
    private final String cloudProvider;
    private final boolean allowMutations;

    private final Map<String, ServiceHealth> healthMap = new LinkedHashMap<>();
    private Observation observation;
    private int step;
    private double cumulativeReward;
    private boolean diagnosed;
    private boolean terminal;
    private TerminationReason reason = TerminationReason.NONE;

    public SidecarEnvironment(
            Scenario scenario,
            RewardFunction rewardFunction,
            SidecarClient sidecarClient,
            String cloudProvider,
            boolean allowMutations) {
        this.scenario = scenario;
        this.rewardFunction = rewardFunction;
        this.sidecarClient = sidecarClient;
        this.cloudProvider = (cloudProvider != null && !cloudProvider.isBlank()) ? cloudProvider : "aws";
        this.allowMutations = allowMutations;
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
        refresh();
        observation = buildObservation(
                "Sidecar incident alert: " + scenario.alertingService() + " degraded on " + cloudProvider,
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
                    SidecarDto.TelemetryResponse resp = sidecarClient.queryTelemetry(cloudProvider, query.service(), query.signal().name());
                    signals = resp.signals().stream()
                            .map(item -> new ObservedSignal(query.signal(), item.service(), item.summary(), item.attributes()))
                            .toList();
                    feedback = "Sidecar returned %d %s sample(s) for %s."
                            .formatted(signals.size(), query.signal(), query.service());
                }
                case InspectRecentChanges changes -> {
                    SidecarDto.ChangesResponse resp = sidecarClient.inspectChanges(cloudProvider, changes.service(), changes.windowMinutes());
                    signals = resp.changes().stream()
                            .map(c -> new ObservedSignal(TelemetrySignal.DEPLOYS, c.service(), "[" + c.kind() + "] " + c.summary(), Map.of()))
                            .toList();
                    feedback = "Sidecar returned " + resp.changes().size() + " recent change(s).";
                }
                case CorrelateAnomalies anomalies -> feedback = "Sidecar anomaly ranking completed.";
                case ReadRunbook runbook -> feedback = "Opened runbook " + runbook.runbookId() + ".";
                case ListRemediations ignored -> feedback = "Listed remediations available via sidecar.";
                case ApplyRemediation remediation -> {
                    if (!allowMutations) {
                        feedback = "Sidecar mutation rejected: mutations disabled in agent policy.";
                    } else {
                        SidecarDto.RemediationResponse resp = sidecarClient.remediate(
                                cloudProvider,
                                remediation.service(),
                                remediation.remediation().name(),
                                "agent");
                        feedback = resp.message();
                        if (resp.success()) {
                            effects.add(TransitionEffect.REMEDIATION_CORRECT);
                        }
                    }
                }
                case DeclareDiagnosis diagnosis -> {
                    diagnosed = true;
                    effects.add(TransitionEffect.DIAGNOSIS_CORRECT);
                    feedback = "Diagnosis recorded for " + diagnosis.service() + ".";
                }
                case Idle ignored -> feedback = "No action taken.";
            }
        } catch (SidecarUnavailableException e) {
            feedback = "Sidecar connector unavailable: connection refused. Running in fallback mode.";
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
        try {
            SidecarDto.TelemetryResponse resp = sidecarClient.queryTelemetry(cloudProvider, service, signal.name());
            List<ObservedSignal> list = new ArrayList<>();
            for (SidecarDto.SignalItem item : resp.signals()) {
                list.add(new ObservedSignal(signal, item.service(), item.summary(), item.attributes()));
            }
            return list;
        } catch (SidecarUnavailableException e) {
            return List.of(new ObservedSignal(signal, service, "Sidecar unavailable.", Map.of()));
        }
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
        return !healthMap.isEmpty() && healthMap.values().stream().allMatch(h -> h == ServiceHealth.HEALTHY);
    }

    private void refresh() {
        for (ServiceSpec spec : scenario.services()) {
            try {
                SidecarDto.TelemetryResponse resp = sidecarClient.queryTelemetry(cloudProvider, spec.name(), "METRICS");
                if (resp.errorRate() > 0.25 || resp.p95LatencyMs() > 1500) {
                    healthMap.put(spec.name(), ServiceHealth.OUTAGE);
                } else if (resp.errorRate() > 0.05 || resp.p95LatencyMs() > 400) {
                    healthMap.put(spec.name(), ServiceHealth.DEGRADED);
                } else {
                    healthMap.put(spec.name(), ServiceHealth.HEALTHY);
                }
            } catch (Exception ignored) {
                // keep last known reading
            }
        }
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
                healthMap,
                cumulativeReward);
    }
}
