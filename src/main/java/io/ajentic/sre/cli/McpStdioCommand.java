package io.ajentic.sre.cli;

import io.ajentic.sre.agent.mcp.McpProtocolHandler;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Model Context Protocol stdio transport. Reads newline-delimited JSON-RPC 2.0 requests from stdin and writes each
 * response to stdout, which is how MCP hosts launch local servers.
 */
@Unremovable
@Command(
        name = "mcp",
        mixinStandardHelpOptions = true,
        description = "Serve the agent tools over the Model Context Protocol on stdin/stdout.")
public class McpStdioCommand implements Runnable {

    @Inject
    McpProtocolHandler handler;

    @Override
    public void run() {
        try {
            Thread.ofVirtual().start(this::pump).join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void pump() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(
                        new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String response = handler.handle(line);
                if (response != null) {
                    writer.println(response);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
