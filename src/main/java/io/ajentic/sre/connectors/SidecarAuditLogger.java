package io.ajentic.sre.connectors;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Appends audit log entries for every mutating action executed by the sidecar to .ajentic/audit.jsonl.
 */
@ApplicationScoped
public class SidecarAuditLogger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path auditFilePath;

    public SidecarAuditLogger() {
        this(".ajentic/audit.jsonl");
    }

    public SidecarAuditLogger(@ConfigProperty(name = "ajentic.sidecar.audit-file", defaultValue = ".ajentic/audit.jsonl") String path) {
        this.auditFilePath = Path.of(path);
    }

    public synchronized void recordMutation(String actor, String tool, String target, String result, String summary) {
        try {
            if (auditFilePath.getParent() != null) {
                Files.createDirectories(auditFilePath.getParent());
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("actor", actor != null ? actor : "agent");
            entry.put("tool", tool);
            entry.put("target", target);
            entry.put("result", result);
            entry.put("summary", summary);

            String jsonLine = MAPPER.writeValueAsString(entry) + System.lineSeparator();
            Files.writeString(
                    auditFilePath,
                    jsonLine,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Non-fatal, audit log failure should not crash operation
            System.err.println("Failed to write sidecar audit log: " + e.getMessage());
        }
    }
}
