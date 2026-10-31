package io.ajentic.sre.simulation;

import io.ajentic.sre.simulation.action.AgentAction;

import java.util.List;

public interface Environment {

    EnvironmentSpec spec();

    Observation reset(ResetRequest request);

    StepResult step(AgentAction action);

    List<ObservedSignal> signals(TelemetrySignal signal, String service);

    Observation observe();

    boolean isTerminal();

    boolean isDiagnosed();

    boolean isResolved();
}
