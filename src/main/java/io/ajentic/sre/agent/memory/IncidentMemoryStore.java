package io.ajentic.sre.agent.memory;

import io.ajentic.sre.agent.masking.MaskingSession;
import io.ajentic.sre.agent.masking.SensitiveDataMasker;
import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.rl.episode.StepRecord;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ScenarioRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Append-only episodic memory of completed incidents: one JSON line per episode in
 * {@code .ajentic/incidents.jsonl}, fsync'd on every write and rotated when the file grows past
 * {@code ajentic.memory.max-file-size}. Reads are newest-first and tolerate corrupt lines.
 */
@ApplicationScoped
public class IncidentMemoryStore {

    private static final Logger LOG = Logger.getLogger(IncidentMemoryStore.class);
    private static final int MAX_LIMIT = 10;

    private final Path file;
    private final boolean enabled;
    private final long maxFileSize;
    private final SensitiveDataMasker masker;
    private final ObjectMapper mapper;
    private final ScenarioRepository scenarios;

    @Inject
    public IncidentMemoryStore(
            SensitiveDataMasker masker,
            ObjectMapper mapper,
            ScenarioRepository scenarios,
            @ConfigProperty(name = "ajentic.memory.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "ajentic.memory.dir", defaultValue = ".ajentic") String dir,
            @ConfigProperty(name = "ajentic.memory.max-file-size", defaultValue = "1048576") long maxFileSize) {
        this.enabled = enabled;
        this.file = Path.of(dir, "incidents.jsonl");
        this.maxFileSize = maxFileSize;
        this.masker = masker;
        this.mapper = mapper;
        this.scenarios = scenarios;
    }

    /**
     * Persists a completed episode. Failures are logged, never thrown: memory must not break episode runs.
     */
    public void save(EpisodeResult result) {
        if (!enabled) {
            return;
        }
        Scenario scenario = scenarios.byId(result.scenarioId()).orElse(null);
        StoredIncident incident = new StoredIncident(
                result.episodeId(),
                result.scenarioId(),
                scenario == null ? result.scenarioId() : scenario.title(),
                scenario == null || scenario.faults().isEmpty() ? "UNKNOWN" : scenario.faults().getFirst().kind().name(),
                scenario == null ? "" : scenario.culpritService(),
                diagnosisOf(result),
                remediationOf(result),
                result.solved(),
                result.diagnosed(),
                result.totalReward(),
                result.steps(),
                Instant.now());
        incident = maskTexts(incident);
        try {
            synchronized (this) {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (Files.exists(file) && Files.size(file) > maxFileSize) {
                    rotate();
                }
                byte[] line = (mapper.writeValueAsString(incident) + "\n").getBytes(StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    channel.write(java.nio.ByteBuffer.wrap(line));
                    channel.force(true);
                }
            }
        } catch (IOException e) {
            LOG.warnf("Could not persist incident memory: %s", e.getMessage());
        }
    }

    /**
     * Searches persisted incidents, newest first. Blank filters are ignored; the query matches case-insensitively
     * against the stored text fields.
     */
    public List<StoredIncident> search(String query, String service, String faultKind, int limit) {
        if (!enabled || !Files.exists(file)) {
            return List.of();
        }
        int max = Math.min(Math.max(limit, 1), MAX_LIMIT);
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<StoredIncident> matches = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0 && matches.size() < max; i--) {
                StoredIncident incident = readLine(lines.get(i));
                if (incident == null) {
                    continue;
                }
                if (isBlank(service) || incident.service().equalsIgnoreCase(service)) {
                    if (isBlank(faultKind) || incident.faultKind().equalsIgnoreCase(faultKind)) {
                        if (isBlank(query) || haystack(incident).contains(query.toLowerCase(Locale.ROOT))) {
                            matches.add(incident);
                        }
                    }
                }
            }
            return matches;
        } catch (IOException e) {
            LOG.warnf("Could not read incident memory: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * A short summary of past incidents for the same service and fault kind, for injection into the system
     * prompt. Empty when there is nothing relevant.
     */
    public String summaryFor(Scenario scenario) {
        if (!enabled || scenario == null || scenario.faults().isEmpty()) {
            return "";
        }
        List<StoredIncident> past = search("", scenario.culpritService(), scenario.faults().getFirst().kind().name(), 2);
        if (past.isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder("Similar past incidents:");
        for (StoredIncident incident : past) {
            summary.append("\n- ").append(incident.scenarioId())
                    .append(": solved=").append(incident.solved())
                    .append(", reward=").append(String.format(Locale.ROOT, "%.2f", incident.reward()));
            if (!incident.diagnosis().isBlank()) {
                summary.append(", diagnosis: ").append(incident.diagnosis());
            }
            if (!incident.remediation().isBlank()) {
                summary.append(", remediation: ").append(incident.remediation());
            }
        }
        return summary.toString().length() <= 500 ? summary.toString() : summary.substring(0, 500);
    }

    public Path file() {
        return file;
    }

    private StoredIncident readLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(line, StoredIncident.class);
        } catch (Exception e) {
            LOG.warnf("Skipping corrupt incident memory line: %s", e.getMessage());
            return null;
        }
    }

    private StoredIncident maskTexts(StoredIncident incident) {
        MaskingSession session = masker.newSession();
        return new StoredIncident(
                incident.episodeId(),
                incident.scenarioId(),
                session.mask(incident.title()),
                incident.faultKind(),
                incident.service(),
                session.mask(incident.diagnosis()),
                session.mask(incident.remediation()),
                incident.solved(),
                incident.diagnosed(),
                incident.reward(),
                incident.steps(),
                incident.completedAt());
    }

    private void rotate() {
        try {
            long rotated = 0L;
            while (Files.exists(sibling(rotated))) {
                rotated++;
            }
            Files.move(file, sibling(rotated), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warnf("Could not rotate incident memory: %s", e.getMessage());
        }
    }

    private Path sibling(long index) {
        return file.resolveSibling(file.getFileName() + "." + index);
    }

    private static String diagnosisOf(EpisodeResult result) {
        String diagnosis = "";
        for (StepRecord step : result.trajectory()) {
            if (step.action().startsWith("declare ")) {
                diagnosis = step.action();
            }
        }
        return diagnosis;
    }

    private static String remediationOf(EpisodeResult result) {
        String remediation = "";
        for (StepRecord step : result.trajectory()) {
            if (step.action().startsWith("apply ")) {
                remediation = step.action();
            }
        }
        return remediation;
    }

    private static String haystack(StoredIncident incident) {
        return (incident.title() + " " + incident.scenarioId() + " " + incident.service() + " "
                + incident.faultKind() + " " + incident.diagnosis() + " " + incident.remediation())
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
