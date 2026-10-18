package io.ajentic.sre.schedule.engine;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "ajentic.scheduler")
public interface SchedulerSettings {

    @WithDefault("4")
    int maxConcurrentRuns();

    @WithDefault("30s")
    Duration recoveryDelay();

    @WithDefault("1s")
    Duration tickInterval();

    @WithDefault("UTC")
    String zone();
}
