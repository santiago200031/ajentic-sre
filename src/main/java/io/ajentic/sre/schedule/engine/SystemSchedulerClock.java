package io.ajentic.sre.schedule.engine;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;

import java.time.Instant;

@Default
@DefaultBean
@ApplicationScoped
public class SystemSchedulerClock implements SchedulerClock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
