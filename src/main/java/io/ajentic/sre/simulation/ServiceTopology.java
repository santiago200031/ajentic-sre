package io.ajentic.sre.simulation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The validated service dependency DAG for one scenario. A service's {@code dependencies} are the downstream
 * services it calls, so fault effects cascade from a dependency upstream to its dependents. Construction fails
 * fast on an unknown reference or a cycle.
 */
public final class ServiceTopology {

    private final Map<String, List<String>> dependencies;

    private ServiceTopology(Map<String, List<String>> dependencies) {
        this.dependencies = dependencies;
    }

    public static ServiceTopology of(List<ServiceSpec> services) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (ServiceSpec spec : services) {
            edges.put(spec.name(), List.copyOf(spec.dependencies()));
        }
        edges.forEach((name, deps) -> deps.forEach(dep -> {
            if (!edges.containsKey(dep)) {
                throw new IllegalArgumentException("Service '" + name + "' depends on unknown service '" + dep + "'");
            }
        }));
        detectCycles(edges);
        return new ServiceTopology(edges);
    }

    public List<String> dependenciesOf(String name) {
        return dependencies.getOrDefault(name, List.of());
    }

    /**
     * Every service that transitively depends on {@code name} (i.e. calls it, directly or through other services),
     * ordered by hop count with direct dependents first.
     */
    public List<String> ancestorsOf(String name) {
        List<String> ancestors = new java.util.ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> frontier = reverseDependenciesOf(name);
        while (!frontier.isEmpty()) {
            List<String> next = new java.util.ArrayList<>();
            for (String candidate : frontier) {
                if (!seen.add(candidate)) {
                    continue;
                }
                ancestors.add(candidate);
                next.addAll(reverseDependenciesOf(candidate));
            }
            frontier = next;
        }
        return ancestors;
    }

    private List<String> reverseDependenciesOf(String name) {
        return dependencies.entrySet().stream()
                .filter(entry -> entry.getValue().contains(name))
                .map(Map.Entry::getKey)
                .toList();
    }

    public Map<String, List<String>> edges() {
        return Map.copyOf(dependencies);
    }

    private static void detectCycles(Map<String, List<String>> edges) {
        Map<String, Integer> color = new LinkedHashMap<>();
        edges.keySet().forEach(node -> color.put(node, 0));
        for (String start : edges.keySet()) {
            if (color.get(start) == 0) {
                visit(start, edges, color, new java.util.ArrayList<>());
            }
        }
    }

    private static void visit(String node, Map<String, List<String>> edges, Map<String, Integer> color,
            List<String> path) {
        color.put(node, 1);
        path.add(node);
        for (String dep : edges.getOrDefault(node, List.of())) {
            int state = color.get(dep);
            if (state == 1) {
                throw new IllegalArgumentException(
                        "Circular service dependencies: " + String.join(" -> ", path) + " -> " + dep);
            }
            if (state == 0) {
                visit(dep, edges, color, path);
            }
        }
        path.removeLast();
        color.put(node, 2);
    }
}
