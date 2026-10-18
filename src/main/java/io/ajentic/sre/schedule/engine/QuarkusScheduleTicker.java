package io.ajentic.sre.schedule.engine;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Drives {@link Scheduler#tick()} in server mode on a virtual thread. The CLI and tests keep using
 * {@link Scheduler} directly so they stay deterministic.
 */
@ApplicationScoped
public class QuarkusScheduleTicker {

    private final Scheduler scheduler;

    @Inject
    public QuarkusScheduleTicker(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(
            every = "${ajentic.scheduler.tick-interval:1s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @RunOnVirtualThread
    void tick() {
        scheduler.tick();
    }
}
