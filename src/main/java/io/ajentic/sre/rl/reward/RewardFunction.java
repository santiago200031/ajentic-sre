package io.ajentic.sre.rl.reward;

@FunctionalInterface
public interface RewardFunction {

    double reward(Transition transition);
}
