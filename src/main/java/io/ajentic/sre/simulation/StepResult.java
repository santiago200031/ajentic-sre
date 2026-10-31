package io.ajentic.sre.simulation;

public record StepResult(Observation observation, double reward, TerminationReason reason) {

    public boolean done() {
        return reason != TerminationReason.NONE;
    }
}
