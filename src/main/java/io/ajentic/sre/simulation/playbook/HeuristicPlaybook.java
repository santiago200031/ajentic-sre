package io.ajentic.sre.simulation.playbook;

import io.ajentic.sre.simulation.action.AgentAction;
import io.ajentic.sre.simulation.action.ApplyRemediation;
import io.ajentic.sre.simulation.action.DeclareDiagnosis;
import io.ajentic.sre.simulation.action.Idle;
import io.ajentic.sre.simulation.action.ListRemediations;
import io.ajentic.sre.simulation.Environment;
import io.ajentic.sre.simulation.Observation;
import io.ajentic.sre.simulation.ObservedSignal;
import io.ajentic.sre.simulation.action.QueryTelemetry;
import io.ajentic.sre.simulation.TelemetrySignal;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Dependent
@PlaybookModel(PlaybookKind.HEURISTIC)
public final class HeuristicPlaybook implements Playbook {

    private static final List<TelemetrySignal> INVESTIGATION = List.of(
            TelemetrySignal.METRICS,
            TelemetrySignal.LOGS,
            TelemetrySignal.TRACES,
            TelemetrySignal.DEPLOYS);

    private final FaultClassifier classifier;
    private final Deque<TelemetrySignal> pending = new ArrayDeque<>(INVESTIGATION);
    private final Deque<String> pendingTargets = new ArrayDeque<>();

    public HeuristicPlaybook() {
        this(new FaultClassifier());
    }

    @Inject
    public HeuristicPlaybook(FaultClassifier classifier) {
        this.classifier = classifier;
    }
    private final Set<ObservedSignal> collected = new LinkedHashSet<>();

    private String target;
    private Diagnosis diagnosis;
    private boolean remediationsListed;
    private boolean remediationApplied;
    private boolean diagnosisDeclared;

    @Override
    public AgentAction next(Environment environment, Observation observation) {
        if (observation.terminal()) {
            return new Idle();
        }

        collected.addAll(observation.signals());
        if (target == null) {
            target = resolveTarget(observation);
            observation.serviceHealth().keySet().stream()
                    .filter(name -> !name.equals(target))
                    .forEach(pendingTargets::add);
        }

        if (diagnosis == null && !pending.isEmpty()) {
            return new QueryTelemetry(pending.poll(), target);
        }

        if (diagnosis == null) {
            diagnosis = classifier.classify(new ArrayList<>(collected)).orElse(null);
            if (diagnosis == null && !pendingTargets.isEmpty()) {
                target = pendingTargets.poll();
                return new QueryTelemetry(TelemetrySignal.LOGS, target);
            }
            if (diagnosis == null) {
                if (!remediationsListed) {
                    remediationsListed = true;
                    return new ListRemediations();
                }
                return new Idle();
            }
        }

        if (!diagnosisDeclared) {
            diagnosisDeclared = true;
            return new DeclareDiagnosis(diagnosis.component(), diagnosis.rootCause());
        }

        if (!remediationApplied) {
            remediationApplied = true;
            return new ApplyRemediation(diagnosis.remediation(), diagnosis.component());
        }

        return new Idle();
    }

    private static String resolveTarget(Observation observation) {
        return observation.signals().stream()
                .map(ObservedSignal::service)
                .findFirst()
                .orElseGet(() -> observation.serviceHealth().keySet().stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No service available to investigate")));
    }
}
