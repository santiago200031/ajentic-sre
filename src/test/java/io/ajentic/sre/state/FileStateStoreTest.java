package io.ajentic.sre.state;

import io.ajentic.sre.schedule.store.FileScheduleStore;
import io.ajentic.sre.schedule.store.ScheduleDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStateStoreTest {

    private static final Path TEST_DIR = Path.of(".ajentic", "test-state-unit");
    private static final Path STATE_FILE = TEST_DIR.resolve("state.json");
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void cleanup() throws IOException {
        if (Files.exists(TEST_DIR)) {
            try (var stream = Files.walk(TEST_DIR)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Test
    void schedulesAndVersionsSurviveRestart() throws IOException {
        FileStateStore store1 = new FileStateStore(mapper, STATE_FILE);
        FileScheduleStore scheduleStore1 = new FileScheduleStore(store1);

        ScheduleDefinition created = scheduleStore1.add(new ScheduleDefinition(
                null,
                "nightly-drill",
                "0 2 * * *",
                true,
                false,
                0L,
                null,
                ZoneId.of("UTC"),
                "orders-bad-deployment"));

        assertEquals(1L, created.version());
        assertEquals("nightly-drill", created.name());

        ScheduleDefinition updated = scheduleStore1.update(created.id(), 1L, current -> current.withCron("0 3 * * *"));
        assertEquals(2L, updated.version());
        assertEquals("0 3 * * *", updated.cron());

        // Validate JSON content on disk
        assertTrue(Files.exists(STATE_FILE));
        JsonNode json = mapper.readTree(STATE_FILE.toFile());
        assertEquals(1, json.get("schemaVersion").asInt());
        assertEquals(1, json.get("schedules").size());
        assertEquals(2L, json.get("schedules").get(0).get("version").asLong());
        assertEquals("0 3 * * *", json.get("schedules").get(0).get("cron").asText());

        // Simulate app restart with a fresh store instance pointing to the same file
        FileStateStore store2 = new FileStateStore(mapper, STATE_FILE);
        FileScheduleStore scheduleStore2 = new FileScheduleStore(store2);

        List<ScheduleDefinition> restored = scheduleStore2.all();
        assertEquals(1, restored.size());
        ScheduleDefinition reloaded = restored.getFirst();
        assertEquals(created.id(), reloaded.id());
        assertEquals("nightly-drill", reloaded.name());
        assertEquals(2L, reloaded.version());
        assertEquals("0 3 * * *", reloaded.cron());
        assertEquals("orders-bad-deployment", reloaded.scenarioId());

        assertTrue(scheduleStore2.byId(created.id()).isPresent());
        assertTrue(scheduleStore2.byName("nightly-drill").isPresent());
    }

    @Test
    void drillHistorySurvivesRestart() {
        FileStateStore store1 = new FileStateStore(mapper, STATE_FILE);
        io.ajentic.sre.rl.episode.EpisodeResult result = new io.ajentic.sre.rl.episode.EpisodeResult(
                "ep-123",
                "orders-bad-deployment",
                true,
                true,
                true,
                1.5,
                6,
                io.ajentic.sre.rl.TerminationReason.RESOLVED,
                List.of(new io.ajentic.sre.rl.episode.StepRecord(0, "check metrics", 0.1, "ok", false)));

        store1.saveDrills(List.of(result));
        assertEquals(1, store1.loadDrills().size());

        FileStateStore store2 = new FileStateStore(mapper, STATE_FILE);
        List<io.ajentic.sre.rl.episode.EpisodeResult> loaded = store2.loadDrills();
        assertEquals(1, loaded.size());
        assertEquals("ep-123", loaded.getFirst().episodeId());
        assertEquals("orders-bad-deployment", loaded.getFirst().scenarioId());
        assertTrue(loaded.getFirst().solved());
        assertEquals(1, loaded.getFirst().trajectory().size());
        assertEquals("check metrics", loaded.getFirst().trajectory().getFirst().action());
    }

    @Test
    void crashSafetyIgnoresPartialTempFiles() throws IOException {
        Files.createDirectories(TEST_DIR);
        Path orphanTemp = TEST_DIR.resolve("state.json.tmp.corrupt-orphan");
        Files.writeString(orphanTemp, "partial content from crashed write");

        FileStateStore store = new FileStateStore(mapper, STATE_FILE);
        // Existing state is empty
        assertEquals(0, store.loadSchedules().size());
        // The orphan temp file is cleaned up
        assertFalse(Files.exists(orphanTemp));

        // Normal writes still succeed
        store.saveSchedules(List.of(new ScheduleDefinition(
                "s1", "test", "0 0 * * *", true, false, 1L, "g1", ZoneId.of("UTC"), "orders-bad-deployment")));
        assertEquals(1, store.loadSchedules().size());
    }

    @Test
    void corruptStateFileFallsBackSafelyToEmpty() throws IOException {
        Files.createDirectories(TEST_DIR);
        Files.writeString(STATE_FILE, "{ corrupt json");

        FileStateStore store = new FileStateStore(mapper, STATE_FILE);
        assertEquals(0, store.loadSchedules().size());
        assertEquals(0, store.loadDrills().size());
    }

    @Test
    void concurrentReadsAndUpdatesDoNotThrowOverlappingLockException() throws InterruptedException {
        FileStateStore store = new FileStateStore(mapper, STATE_FILE);
        int threadCount = 8;
        int operationsPerThread = 20;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            pool.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        if (threadId % 2 == 0) {
                            store.saveSchedules(List.of(new ScheduleDefinition(
                                    "s-" + threadId + "-" + j,
                                    "task",
                                    "0 0 * * *",
                                    true,
                                    false,
                                    (long) j,
                                    null,
                                    ZoneId.of("UTC"),
                                    "orders-bad-deployment")));
                        } else {
                            store.loadSchedules();
                        }
                    }
                } catch (Throwable t) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, errors.get(), "Concurrent access threw errors");
    }

    @Test
    void unimplementedSeamsThrowUnsupportedOperation() {
        JdbcStateStore jdbc = new JdbcStateStore();
        assertThrows(UnsupportedOperationException.class, jdbc::loadSchedules);
        assertThrows(UnsupportedOperationException.class, () -> jdbc.saveSchedules(List.of()));
        assertThrows(UnsupportedOperationException.class, jdbc::loadDrills);
        assertThrows(UnsupportedOperationException.class, () -> jdbc.saveDrills(List.of()));

        RedisStateStore redis = new RedisStateStore();
        assertThrows(UnsupportedOperationException.class, redis::loadSchedules);
        assertThrows(UnsupportedOperationException.class, () -> redis.saveSchedules(List.of()));
        assertThrows(UnsupportedOperationException.class, redis::loadDrills);
        assertThrows(UnsupportedOperationException.class, () -> redis.saveDrills(List.of()));
    }
}
