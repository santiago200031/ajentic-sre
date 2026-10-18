package io.ajentic.sre.cli;

import io.ajentic.sre.rl.simulation.ScenarioRepository;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

@Unremovable
@Command(name = "scenarios", mixinStandardHelpOptions = true, description = "List the available incident scenarios.")
public class ScenariosCommand implements Runnable {

    @Inject
    ScenarioRepository scenarios;

    @Override
    public void run() {
        scenarios.all().forEach(scenario ->
                System.out.printf("%-30s %s%n", scenario.id(), scenario.title()));
    }
}
