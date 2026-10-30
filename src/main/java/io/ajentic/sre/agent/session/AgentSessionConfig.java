package io.ajentic.sre.agent.session;

import io.ajentic.sre.agent.llm.TokenUsage;

public record AgentSessionConfig(String scenarioId, long seed, String model, int maxIterations, int maxTokens) {

    private static final int DEFAULT_MAX_ITERATIONS = 8;

    public AgentSessionConfig(String scenarioId, long seed, String model, int maxIterations) {
        this(scenarioId, seed, model, maxIterations, 0);
    }

    public static AgentSessionConfig forScenario(String scenarioId) {
        return new AgentSessionConfig(scenarioId, 42L, null, DEFAULT_MAX_ITERATIONS);
    }

    public AgentSessionConfig withModel(String model) {
        return new AgentSessionConfig(scenarioId, seed, model, maxIterations, maxTokens);
    }

    public AgentSessionConfig withMaxTokens(int tokens) {
        return new AgentSessionConfig(scenarioId, seed, model, maxIterations, tokens);
    }
}
