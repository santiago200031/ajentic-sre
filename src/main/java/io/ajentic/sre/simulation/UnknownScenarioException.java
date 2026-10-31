package io.ajentic.sre.simulation;

public class UnknownScenarioException extends RuntimeException {

    public UnknownScenarioException(String scenarioId) {
        super("Unknown scenario: " + scenarioId);
    }
}
