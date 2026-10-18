package io.ajentic.sre.cli;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Quarkus;
import picocli.CommandLine.Command;

@Unremovable
@Command(name = "serve", mixinStandardHelpOptions = true, description = "Start the HTTP API and block.")
public class ServeCommand implements Runnable {

    @Override
    public void run() {
        Quarkus.waitForExit();
    }
}
