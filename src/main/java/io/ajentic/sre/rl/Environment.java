package io.ajentic.sre.rl;

import io.ajentic.sre.rl.action.AgentAction;

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
