package io.ajentic.sre.simulation.drill;

import io.ajentic.sre.simulation.Environment;

import java.util.Optional;

public final class SimulationHandle {

    private final String id;
    private final Environment environment;

    private volatile SimulationResult result;

    public SimulationHandle(String id, Environment environment) {
        this.id = id;
        this.environment = environment;
    }

    public String id() {
        return id;
    }

    public Environment environment() {
        return environment;
    }

    public Optional<SimulationResult> result() {
        return Optional.ofNullable(result);
    }

    public void complete(SimulationResult episodeResult) {
        this.result = episodeResult;
    }
}
