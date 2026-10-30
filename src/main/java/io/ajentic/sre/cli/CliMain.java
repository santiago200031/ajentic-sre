package io.ajentic.sre.cli;

import io.quarkus.picocli.runtime.PicocliRunner;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Starts the HTTP stack only for {@code serve}. Every other command runs headless, so a CLI or MCP process can run
 * alongside a server on :8080 and keep stdout clean for the JSON-RPC stream.
 */
@QuarkusMain
public class CliMain {

    public static void main(String... args) {
        if (headless(args)) {
            System.setProperty("quarkus.http.host-enabled", "false");
        }
        Quarkus.run(PicocliRunner.class, args);
    }

    static boolean headless(String... args) {
        return args.length > 0 && !"serve".equals(args[0]) && !"swagger".equals(args[0]);
    }
}
