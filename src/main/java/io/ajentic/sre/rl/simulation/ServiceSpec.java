package io.ajentic.sre.rl.simulation;

public record ServiceSpec(String name, ServiceKind kind, int replicas) {
}
