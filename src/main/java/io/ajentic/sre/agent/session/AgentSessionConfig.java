package io.ajentic.sre.agent.session;

public record AgentSessionConfig(String scenarioId, long seed, String model, int maxIterations) {

    private static final int DEFAULT_MAX_ITERATIONS = 8;

    public static AgentSessionConfig forScenario(String scenarioId) {
        return new AgentSessionConfig(scenarioId, 42L, null, DEFAULT_MAX_ITERATIONS);
    }

    public AgentSessionConfig withModel(String model) {
        return new AgentSessionConfig(scenarioId, seed, model, maxIterations);
    }
}
