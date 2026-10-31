package io.ajentic.sre.simulation.playbook;

import io.ajentic.sre.simulation.Environment;
import io.ajentic.sre.simulation.Observation;
import io.ajentic.sre.simulation.TelemetrySignal;
import io.ajentic.sre.simulation.action.AgentAction;
import io.ajentic.sre.simulation.action.QueryTelemetry;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Benchmark baseline: picks a random telemetry query each step, deterministic per seed.
 */
@Dependent
@PlaybookModel(PlaybookKind.RANDOM)
public final class RandomPlaybook implements Playbook {

    private final RandomGenerator random;

    private RandomPlaybook(RandomGenerator random) {
        this.random = random;
    }

    @Inject
    public RandomPlaybook(@ConfigProperty(name = "ajentic.policy.random.seed", defaultValue = "42") long seed) {
        this(RandomGeneratorFactory.of("L64X128MixRandom").create(seed));
    }

    public static RandomPlaybook withSeed(long seed) {
        return new RandomPlaybook(RandomGeneratorFactory.of("L64X128MixRandom").create(seed));
    }

    @Override
    public AgentAction next(Environment environment, Observation observation) {
        List<String> services = List.copyOf(observation.serviceHealth().keySet());
        TelemetrySignal[] signals = TelemetrySignal.values();
        return new QueryTelemetry(signals[random.nextInt(signals.length)], services.get(random.nextInt(services.size())));
    }
}
