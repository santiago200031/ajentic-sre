package io.ajentic.sre.simulation.drill;

public class UnknownSimulationException extends RuntimeException {

    public UnknownSimulationException(String id) {
        super("Unknown episode: " + id);
    }
}
