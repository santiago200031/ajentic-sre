package io.ajentic.sre.schedule.drill;

import io.ajentic.sre.schedule.engine.ScheduledJob;
import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.rl.episode.EpisodeRunner;
import io.ajentic.sre.rl.policy.PolicyFactory;
import io.ajentic.sre.rl.simulation.EnvironmentFactory;
import io.ajentic.sre.state.InMemoryStateStore;
import io.ajentic.sre.state.StateStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class IncidentDrillRunner {

    private static final int HISTORY_LIMIT = 50;

    private final EnvironmentFactory environments;
    private final EpisodeRunner runner;
    private final PolicyFactory policies;
    private final StateStore stateStore;
    private final List<EpisodeResult> history = new CopyOnWriteArrayList<>();

    public IncidentDrillRunner(EnvironmentFactory environments, EpisodeRunner runner, PolicyFactory policies) {
        this(environments, runner, policies, new InMemoryStateStore());
    }

    @Inject
    public IncidentDrillRunner(
            EnvironmentFactory environments,
            EpisodeRunner runner,
            PolicyFactory policies,
            StateStore stateStore) {
        this.environments = environments;
        this.runner = runner;
        this.policies = policies;
        this.stateStore = stateStore;
        List<EpisodeResult> loaded = stateStore.loadDrills();
        if (loaded != null && !loaded.isEmpty()) {
            history.addAll(loaded);
        }
    }

    public EpisodeResult drill(String scenarioId) {
        return drill(scenarioId, null);
    }

    public EpisodeResult drill(String scenarioId, EpisodeRunner.StepListener listener) {
        Environment environment = environments.create(scenarioId, 42L);
        EpisodeResult result = runner.run(
                UUID.randomUUID().toString(), environment, policies.byName("heuristic"), listener);
        history.add(result);
        while (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
        stateStore.saveDrills(List.copyOf(history));
        return result;
    }

    public ScheduledJob job(String scenarioId) {
        return context -> drill(scenarioId);
    }

    public List<EpisodeResult> recent() {
        return List.copyOf(history);
    }
}
