package io.ajentic.sre.simulation.scorecard;

@FunctionalInterface
public interface Scorecard {

    double reward(Transition transition);
}
