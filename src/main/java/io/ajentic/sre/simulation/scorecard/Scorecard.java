package io.ajentic.sre.simulation.scorecard;

@FunctionalInterface
public interface Scorecard {

    double score(Transition transition);

    default double reward(Transition transition) {
        return score(transition);
    }
}
