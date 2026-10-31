package io.ajentic.sre.state;

import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.schedule.store.ScheduleDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * File-backed state store persisted to {@code .ajentic/state.json}. Uses process-level file locks, atomic
 * temp-file renames, and a schema version field to ensure crash safety and concurrent safety.
 */
@ApplicationScoped
@Typed(FileStateStore.class)
public class FileStateStore implements StateStore {

    private static final Logger LOG = Logger.getLogger(FileStateStore.class);

    private final Path file;
    private final Path lockFile;
    private final ObjectMapper mapper;

    @Inject
    public FileStateStore(
            ObjectMapper mapper,
            @ConfigProperty(name = "ajentic.state.file", defaultValue = ".ajentic/state.json") String filePath) {
        this(mapper, Path.of(filePath));
    }

    public FileStateStore(ObjectMapper mapper, Path file) {
        this.mapper = mapper;
        this.file = file.toAbsolutePath();
        this.lockFile = this.file.resolveSibling(this.file.getFileName().toString() + ".lock");
        cleanStaleTempFiles();
    }

    public Path file() {
        return file;
    }

    @Override
    public List<ScheduleDefinition> loadSchedules() {
        return readSnapshot().schedules();
    }

    @Override
    public void saveSchedules(List<ScheduleDefinition> schedules) {
        updateSnapshot(current -> new StateSnapshot(
                StateSnapshot.CURRENT_SCHEMA_VERSION,
                schedules,
                current.drills()));
    }

    @Override
    public List<EpisodeResult> loadDrills() {
        return readSnapshot().drills();
    }

    @Override
    public void saveDrills(List<EpisodeResult> drills) {
        updateSnapshot(current -> new StateSnapshot(
                StateSnapshot.CURRENT_SCHEMA_VERSION,
                current.schedules(),
                drills));
    }

    private synchronized StateSnapshot readSnapshot() {
        if (!Files.exists(file)) {
            return StateSnapshot.empty();
        }
        try {
            ensureParentDirectory();
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return readFromFileUnderLock();
            }
        } catch (Exception e) {
            LOG.warnf("Could not acquire lock to read state from %s: %s", file, e.getMessage());
            return StateSnapshot.empty();
        }
    }

    private synchronized void updateSnapshot(UnaryOperator<StateSnapshot> updater) {
        try {
            ensureParentDirectory();
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                StateSnapshot current = readFromFileUnderLock();
                StateSnapshot updated = updater.apply(current);
                writeAtomicUnderLock(updated);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to update state file %s: %s", file, e.getMessage());
        }
    }

    private StateSnapshot readFromFileUnderLock() {
        if (!Files.exists(file)) {
            return StateSnapshot.empty();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return StateSnapshot.empty();
            }
            return mapper.readValue(content, StateSnapshot.class);
        } catch (Exception e) {
            LOG.warnf("State file %s was unreadable or corrupt, using empty state: %s", file, e.getMessage());
            return StateSnapshot.empty();
        }
    }

    private void writeAtomicUnderLock(StateSnapshot snapshot) throws IOException {
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
        Path tempFile = file.resolveSibling(file.getFileName().toString() + ".tmp." + UUID.randomUUID());
        try {
            Files.writeString(tempFile, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
        }
    }

    private void ensureParentDirectory() throws IOException {
        Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    private void cleanStaleTempFiles() {
        Path parent = file.getParent();
        if (parent == null || !Files.exists(parent)) {
            return;
        }
        String prefix = file.getFileName().toString() + ".tmp.";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, p -> p.getFileName().toString().startsWith(prefix))) {
            for (Path temp : stream) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }
}
