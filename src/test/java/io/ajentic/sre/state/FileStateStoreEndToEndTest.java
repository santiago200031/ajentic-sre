package io.ajentic.sre.state;

import io.ajentic.sre.schedule.store.ScheduleDefinition;
import io.ajentic.sre.schedule.store.ScheduleStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(FileStateStoreEndToEndTest.FileStoreProfile.class)
class FileStateStoreEndToEndTest {

    private static final String DIR = ".ajentic/test-state-e2e";
    private static final String FILE = DIR + "/state.json";

    public static class FileStoreProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "ajentic.state.store", "file",
                    "ajentic.state.file", FILE);
        }
    }

    @Inject
    ScheduleStore scheduleStore;

    @Inject
    StateStore stateStore;

    @BeforeEach
    @AfterEach
    void cleanup() throws IOException {
        Path p = Path.of(DIR);
        if (Files.exists(p)) {
            try (var stream = Files.walk(p)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Inject
    io.ajentic.sre.schedule.drill.IncidentDrillRunner drillRunner;

    @Test
    void fileStateStoreIsInjectedAndPersistsChanges() {
        Object unwrappedSchedule = io.quarkus.arc.ClientProxy.unwrap(scheduleStore);
        Object unwrappedState = io.quarkus.arc.ClientProxy.unwrap(stateStore);
        assertTrue(unwrappedSchedule instanceof io.ajentic.sre.schedule.store.FileScheduleStore,
                "expected FileScheduleStore but got " + unwrappedSchedule.getClass());
        assertTrue(unwrappedState instanceof FileStateStore,
                "expected FileStateStore but got " + unwrappedState.getClass());

        ScheduleDefinition created = scheduleStore.add(new ScheduleDefinition(
                null,
                "e2e-drill",
                "0 4 * * *",
                true,
                false,
                0L,
                null,
                ZoneId.of("UTC"),
                "orders-bad-deployment"));

        assertEquals(1L, created.version());
        assertTrue(Files.exists(Path.of(FILE)));

        assertEquals(1, stateStore.loadSchedules().size());
        assertEquals("e2e-drill", stateStore.loadSchedules().getFirst().name());
    }

    @Test
    void drillsArePersistedDurable() {
        assertEquals(0, drillRunner.recent().size());
        drillRunner.drill("orders-bad-deployment");
        assertEquals(1, drillRunner.recent().size());
        assertEquals(1, stateStore.loadDrills().size());
        assertEquals("orders-bad-deployment", stateStore.loadDrills().getFirst().scenarioId());
    }
}
