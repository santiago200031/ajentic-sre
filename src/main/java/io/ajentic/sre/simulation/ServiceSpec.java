package io.ajentic.sre.simulation;

import java.util.List;

/**
 * A service declaration. {@code dependencies} are the downstream service names this service calls; the scenario's
 * {@link ServiceTopology} validates that they form a DAG.
 */
public record ServiceSpec(String name, ServiceKind kind, int replicas, List<String> dependencies) {

    public ServiceSpec {
        dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
    }

    public ServiceSpec(String name, ServiceKind kind, int replicas) {
        this(name, kind, replicas, List.of());
    }
}
