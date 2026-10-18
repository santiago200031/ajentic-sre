package io.ajentic.sre.rl.episode;

import io.ajentic.sre.rl.TerminationReason;
import java.util.List;

public record EpisodeResult(
        String episodeId,
        String scenarioId,
        boolean solved,
        boolean resolved,
        boolean diagnosed,
        double totalReward,
        int steps,
        TerminationReason terminationReason,
        List<StepRecord> trajectory) {

    public EpisodeResult {
        trajectory = List.copyOf(trajectory);
    }
}
