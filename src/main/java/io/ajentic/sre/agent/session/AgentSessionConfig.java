package io.ajentic.sre.agent.session;

public record AgentSessionConfig(
        String scenarioId,
        long seed,
        String model,
        int maxIterations,
        int maxTokens,
        int stagnationThreshold,
        int stagnationEscalateAfter) {

    private static final int DEFAULT_MAX_ITERATIONS = 8;
    private static final int DEFAULT_STAGNATION_THRESHOLD = 2;
    private static final int DEFAULT_STAGNATION_ESCALATE_AFTER = 3;

    public AgentSessionConfig(String scenarioId, long seed, String model, int maxIterations) {
        this(scenarioId, seed, model, maxIterations, 0,
                DEFAULT_STAGNATION_THRESHOLD, DEFAULT_STAGNATION_ESCALATE_AFTER);
    }

    public static AgentSessionConfig forScenario(String scenarioId) {
        return new AgentSessionConfig(scenarioId, 42L, null, DEFAULT_MAX_ITERATIONS);
    }

    public AgentSessionConfig withModel(String model) {
        return new AgentSessionConfig(
                scenarioId, seed, model, maxIterations, maxTokens, stagnationThreshold, stagnationEscalateAfter);
    }

    public AgentSessionConfig withMaxTokens(int tokens) {
        return new AgentSessionConfig(
                scenarioId, seed, model, maxIterations, tokens, stagnationThreshold, stagnationEscalateAfter);
    }

    public AgentSessionConfig withStagnation(int threshold, int escalateAfter) {
        return new AgentSessionConfig(
                scenarioId, seed, model, maxIterations, maxTokens, threshold, escalateAfter);
    }
}
