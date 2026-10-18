package io.ajentic.sre.rl.simulation;

public class UnknownScenarioException extends RuntimeException {

    public UnknownScenarioException(String scenarioId) {
        super("Unknown scenario: " + scenarioId);
    }
}
