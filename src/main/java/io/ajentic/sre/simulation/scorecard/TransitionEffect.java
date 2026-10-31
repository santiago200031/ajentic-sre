package io.ajentic.sre.simulation.scorecard;

/**
 * The discrete ways a step can change the state of an incident. Each constant carries the reward it contributes,
 * so the reward function no longer needs to map stringly-typed flags to weights.
 */
public enum TransitionEffect {

    DIAGNOSIS_CORRECT(0.5),
    DIAGNOSIS_INCORRECT(-0.2),
    REMEDIATION_CORRECT(0.6),
    REMEDIATION_INCORRECT(-0.2),
    RESOLVED(0.4),
    CATASTROPHIC(-1.0);

    private final double rewardDelta;

    TransitionEffect(double rewardDelta) {
        this.rewardDelta = rewardDelta;
    }

    public double rewardDelta() {
        return rewardDelta;
    }
}
