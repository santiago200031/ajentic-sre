package io.ajentic.sre.simulation.scorecard;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class IncidentScorecard implements Scorecard {

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
