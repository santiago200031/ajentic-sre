package io.ajentic.sre.simulation.drill;

public record StepRecord(int index, String action, double reward, String feedback, boolean terminal) {

    public double score() {
        return reward;
    }
}
