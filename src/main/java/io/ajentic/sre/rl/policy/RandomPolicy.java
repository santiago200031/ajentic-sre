package io.ajentic.sre.rl.policy;

import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.Observation;
import io.ajentic.sre.rl.TelemetrySignal;
import io.ajentic.sre.rl.action.AgentAction;
import io.ajentic.sre.rl.action.QueryTelemetry;
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
@PolicyModel(PolicyKind.RANDOM)
public final class RandomPolicy implements Policy {

    private final RandomGenerator random;

    private RandomPolicy(RandomGenerator random) {
        this.random = random;
    }

    @Inject
    public RandomPolicy(@ConfigProperty(name = "ajentic.policy.random.seed", defaultValue = "42") long seed) {
        this(RandomGeneratorFactory.of("L64X128MixRandom").create(seed));
    }

    public static RandomPolicy withSeed(long seed) {
        return new RandomPolicy(RandomGeneratorFactory.of("L64X128MixRandom").create(seed));
    }

    @Override
    public AgentAction next(Environment environment, Observation observation) {
        List<String> services = List.copyOf(observation.serviceHealth().keySet());
        TelemetrySignal[] signals = TelemetrySignal.values();
        return new QueryTelemetry(signals[random.nextInt(signals.length)], services.get(random.nextInt(services.size())));
    }
}
