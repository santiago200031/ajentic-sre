package io.ajentic.sre.cli;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import io.quarkus.runtime.Quarkus;
import picocli.CommandLine.Command;

@TopCommand
@Command(
        name = "ajentic-sre",
        mixinStandardHelpOptions = true,
        description = "Open reinforcement learning environment and agent harness for infrastructure incident response.",
        subcommands = {ScenariosCommand.class, SimulateCommand.class, AskCommand.class, SchedulesCommand.class, ServeCommand.class, McpStdioCommand.class})
public class AjenticCommand implements Runnable {

    @Override
    public void run() {
        Quarkus.waitForExit();
    }
}
