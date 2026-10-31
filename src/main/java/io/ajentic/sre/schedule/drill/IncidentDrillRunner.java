package io.ajentic.sre.schedule.drill;

import io.ajentic.sre.schedule.engine.ScheduledJob;
import io.ajentic.sre.simulation.Environment;
import io.ajentic.sre.simulation.drill.SimulationResult;
import io.ajentic.sre.simulation.drill.SimulationRunner;
import io.ajentic.sre.simulation.playbook.PlaybookFactory;
import io.ajentic.sre.simulation.EnvironmentFactory;
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
    private final SimulationRunner runner;
    private final PlaybookFactory policies;
    private final StateStore stateStore;
    private final List<SimulationResult> history = new CopyOnWriteArrayList<>();

    public IncidentDrillRunner(EnvironmentFactory environments, SimulationRunner runner, PlaybookFactory policies) {
        this(environments, runner, policies, new InMemoryStateStore());
    }

    @Inject
    public IncidentDrillRunner(
            EnvironmentFactory environments,
            SimulationRunner runner,
            PlaybookFactory policies,
            StateStore stateStore) {
        this.environments = environments;
        this.runner = runner;
        this.policies = policies;
        this.stateStore = stateStore;
        List<SimulationResult> loaded = stateStore.loadDrills();
        if (loaded != null && !loaded.isEmpty()) {
            history.addAll(loaded);
        }
    }

    public SimulationResult drill(String scenarioId) {
        return drill(scenarioId, null);
    }

    public SimulationResult drill(String scenarioId, SimulationRunner.StepListener listener) {
        Environment environment = environments.create(scenarioId, 42L);
        SimulationResult result = runner.run(
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

    public List<SimulationResult> recent() {
        return List.copyOf(history);
    }
}
