package io.ajentic.sre.cli;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Quarkus;
import picocli.CommandLine.Command;

@Unremovable
@Command(name = "swagger", mixinStandardHelpOptions = true, description = "Print the Swagger UI URL and serve the HTTP API.")
public class SwaggerCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Swagger UI available at: http://localhost:8080/q/swagger-ui");
        Quarkus.waitForExit();
    }
}
