package io.ajentic.sre.rl.simulation;

import io.ajentic.sre.rl.action.AgentAction;
import io.ajentic.sre.rl.action.ApplyRemediation;
import io.ajentic.sre.rl.action.DeclareDiagnosis;
import io.ajentic.sre.rl.reward.TransitionEffect;
import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.EnvironmentSpec;
import io.ajentic.sre.rl.action.Idle;
import io.ajentic.sre.rl.action.ListRemediations;
import io.ajentic.sre.rl.Observation;
import io.ajentic.sre.rl.ObservedSignal;
import io.ajentic.sre.rl.action.QueryTelemetry;
import io.ajentic.sre.rl.action.ReadRunbook;
import io.ajentic.sre.rl.action.RemediationKind;
import io.ajentic.sre.rl.ResetRequest;
import io.ajentic.sre.rl.reward.RewardFunction;
import io.ajentic.sre.rl.StepResult;
import io.ajentic.sre.rl.TelemetrySignal;
import io.ajentic.sre.rl.TerminationReason;
import io.ajentic.sre.rl.reward.Transition;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A deterministic, in-memory incident simulator. Every public method is synchronized so that the virtual threads that
 * fan out read-only tools during an agent turn can safely share one environment instance.
 */
public final class SimulatedProductionEnvironment implements Environment {

    private final Scenario scenario;
    private final RewardFunction rewardFunction;

    private SimulatedSystem system;
    private Observation observation;
    private int step;
    private double cumulativeReward;
    private boolean diagnosed;
    private boolean terminal;
    private TerminationReason reason = TerminationReason.NONE;

    public SimulatedProductionEnvironment(Scenario scenario, long seed, RewardFunction rewardFunction) {
        this.scenario = scenario;
        this.rewardFunction = rewardFunction;
        reset(new ResetRequest(scenario.id(), seed));
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
        this.system = new SimulatedSystem(scenario, request.seed());
        this.step = 0;
        this.cumulativeReward = 0.0;
        this.diagnosed = false;
        this.terminal = false;
        this.reason = TerminationReason.NONE;
        this.observation = buildObservation(
                "Incident detected on " + scenario.culpritService() + ".",
                system.query(TelemetrySignal.METRICS, scenario.culpritService()));
        return observation;
    }

    @Override
    public synchronized StepResult step(AgentAction action) {
        if (terminal) {
            return new StepResult(observation, 0.0, reason);
        }

        Observation before = observation;
        step++;
        system.tick();

        EnumSet<TransitionEffect> effects = EnumSet.noneOf(TransitionEffect.class);
        List<ObservedSignal> signals = List.of();
        String feedback;

        switch (action) {
            case QueryTelemetry query -> {
                signals = system.query(query.signal(), query.service());
                feedback = "Returned %d %s sample(s) for %s."
                        .formatted(signals.size(), query.signal(), query.service());
            }
            case ReadRunbook runbook -> feedback = "Opened runbook " + runbook.runbookId() + ".";
            case ListRemediations ignored -> feedback = "Listed the available remediations.";
            case ApplyRemediation remediation -> {
                RemediationOutcome outcome = system.applyRemediation(remediation.remediation(), remediation.service());
                effects.add(outcome.correct()
                        ? TransitionEffect.REMEDIATION_CORRECT
                        : TransitionEffect.REMEDIATION_INCORRECT);
                feedback = outcome.correct()
                        ? "Remediation " + remediation.remediation() + " is recovering " + remediation.service() + "."
                        : "Remediation " + remediation.remediation() + " did not address the fault on "
                                + remediation.service() + ".";
            }
            case DeclareDiagnosis diagnosis -> {
                boolean correct = system.correctDiagnosis(diagnosis.service(), diagnosis.rootCause());
                if (correct) {
                    diagnosed = true;
                    system.markDiagnosed();
                    effects.add(TransitionEffect.DIAGNOSIS_CORRECT);
                    feedback = "Diagnosis accepted for " + diagnosis.service() + ".";
                } else {
                    effects.add(TransitionEffect.DIAGNOSIS_INCORRECT);
                    feedback = "Diagnosis does not match the fault observed on " + diagnosis.service() + ".";
                }
            }
            case Idle ignored -> feedback = "No action taken.";
        }

        if (system.catastrophic()) {
            terminal = true;
            reason = TerminationReason.CATASTROPHIC;
            effects.add(TransitionEffect.CATASTROPHIC);
        } else if (system.allFaultsResolved()) {
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
        return system.query(signal, service);
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
        return system.allFaultsResolved();
    }

    private Observation buildObservation(String feedback, List<ObservedSignal> signals) {
        return new Observation(
                scenario.id(),
                scenario.incidentSummary(),
                step,
                scenario.maxSteps(),
                terminal,
                system.allFaultsResolved(),
                diagnosed,
                feedback,
                signals,
                scenario.runbooks().stream().map(Runbook::id).toList(),
                List.of(RemediationKind.values()),
                system.health(),
                cumulativeReward);
    }
}
