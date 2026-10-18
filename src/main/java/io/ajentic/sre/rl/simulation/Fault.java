package io.ajentic.sre.rl.simulation;

public record Fault(FaultKind kind, String service, double severity) {

    public static final double DEFAULT_SEVERITY = 0.7;

    public Fault(FaultKind kind, String service) {
        this(kind, service, DEFAULT_SEVERITY);
    }
}
