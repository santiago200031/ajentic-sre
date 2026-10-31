package io.ajentic.sre.health;

import io.ajentic.sre.simulation.ScenarioRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class ReadinessCheck implements HealthCheck {

    private final ScenarioRepository scenarios;

    @Inject
    public ReadinessCheck(ScenarioRepository scenarios) {
        this.scenarios = scenarios;
    }

    @Override
    public HealthCheckResponse call() {
        boolean ready = !scenarios.all().isEmpty();
        return HealthCheckResponse.named("scenario-library")
                .status(ready)
                .withData("scenarios", scenarios.all().size())
                .build();
    }
}
