package io.ajentic.sre.simulation;

final class ActiveFault {

    private final Fault fault;
    private boolean resolved;
    private int resolvedAtStep = -1;

    ActiveFault(Fault fault) {
        this.fault = fault;
    }

    Fault fault() {
        return fault;
    }

    boolean resolved() {
        return resolved;
    }

    void resolve(int step) {
        this.resolved = true;
        this.resolvedAtStep = step;
    }

    int resolvedAtStep() {
        return resolvedAtStep;
    }
}
