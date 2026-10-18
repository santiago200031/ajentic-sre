package io.ajentic.sre.rl.reward;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class IncidentRewardFunction implements RewardFunction {

    public static final double STEP_COST = -0.02;

    @Override
    public double reward(Transition transition) {
        double reward = STEP_COST;
        for (TransitionEffect effect : transition.effects()) {
            reward += effect.rewardDelta();
        }
        return reward;
    }
}
