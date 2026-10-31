package io.ajentic.sre.simulation;

import io.ajentic.sre.simulation.drill.SimulationHandle;
import io.ajentic.sre.simulation.drill.SimulationResult;
import io.ajentic.sre.simulation.drill.SimulationService;
import io.ajentic.sre.simulation.playbook.HeuristicPlaybook;
import io.ajentic.sre.simulation.Scenario;
import io.ajentic.sre.simulation.ScenarioLibrary;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** External scenario packs under {@code ajentic.scenarios.dir} are loaded and merged with the built-ins. */
@QuarkusTest
@WithTestResource(ScenarioPackDirTestResource.class)
class ScenarioPackLoadTest {

    @Inject
    ScenarioLibrary library;

    @Inject
    SimulationService episodes;

    @Test
    void loadsExternalPackAndRunsAnEpisode() {
        Optional<Scenario> external = library.byId("db-pool-exhaustion-sample");

        assertTrue(external.isPresent(), "external pack should be loaded");
        Scenario scenario = external.get();
        assertEquals("samples-db", scenario.services().get(2).name());

        SimulationHandle handle = episodes.start(scenario.id(), 1L);
        SimulationResult result = episodes.run(handle.id(), new HeuristicPlaybook());
        assertTrue(result.solved(), () -> "external episode was not solved: " + result);
        assertTrue(result.diagnosed(), () -> "external root cause was not diagnosed: " + result);
    }

    @Test
    void builtInsTakePrecedenceOverExternalIds() {
        // A pack that reuses a built-in id must not shadow it.
        assertTrue(library.byId("orders-bad-deployment").isPresent());
        assertEquals(7, library.all().size(), "orders-bad-deployment stays a single built-in; one external added");
    }

    @Test
    void brokenPackFailsFastWithFileContext() throws IOException {
        Path dir = Files.createTempDirectory("bad-packs");
        Files.writeString(dir.resolve("broken.yaml"), """
                id: ""
                title: missing id
                services: []
                faults: []
                runbooks: []
                maxSteps: 5
                """);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ScenarioLibrary().loadForTest(dir));
        assertTrue(error.getMessage().contains("broken.yaml"), error.getMessage());
    }

    @Test
    void missingServiceInFaultFailsFast() throws IOException {
        Path dir = Files.createTempDirectory("bad-service-packs");
        Files.writeString(dir.resolve("dangling.yaml"), """
                id: dangling
                title: references a service that does not exist
                services:
                  - {name: only-service, kind: API, replicas: 2}
                faults:
                  - {kind: BAD_DEPLOYMENT, service: ghost-api, severity: 0.8}
                runbooks: []
                maxSteps: 5
                """);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ScenarioLibrary().loadForTest(dir));
        assertTrue(error.getMessage().contains("ghost-api"), error.getMessage());
    }

}
