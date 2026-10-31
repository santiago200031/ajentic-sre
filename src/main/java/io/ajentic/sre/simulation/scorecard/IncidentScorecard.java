package io.ajentic.sre.simulation.scorecard;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class IncidentScorecard implements Scorecard {

    public static final double STEP_COST = -0.02;

    @Override
    public double score(Transition transition) {
        double score = STEP_COST;
        for (TransitionEffect effect : transition.effects()) {
            score += effect.scoreDelta();
        }
        return score;
    }

    @Override
    public double reward(Transition transition) {
        return score(transition);
    }
}
