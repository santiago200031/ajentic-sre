package io.ajentic.sre.rl.episode;

public record StepRecord(int index, String action, double reward, String feedback, boolean terminal) {
}
