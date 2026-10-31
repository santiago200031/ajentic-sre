package io.ajentic.sre.agent.memory;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.ajentic.sre.simulation.drill.SimulationResult;
import io.ajentic.sre.simulation.drill.StepRecord;
import io.ajentic.sre.simulation.TerminationReason;
import io.ajentic.sre.test.WireMockLlmTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Episodic memory: completed episodes are persisted masked to {@code .ajentic/incidents.jsonl}, searchable via
 * {@code search_past_incidents}, and fed back into new sessions' system prompts.
 */
@QuarkusTest
@WithTestResource(WireMockLlmTestResource.class)
@io.quarkus.test.junit.TestProfile(IncidentMemoryEndToEndTest.MemoryDirProfile.class)
class IncidentMemoryEndToEndTest {

    public static class MemoryDirProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("ajentic.memory.dir", DIR);
        }
    }

    @Inject
    IncidentMemoryStore memory;

    // Fixed, git-ignored location: the profile is resolved by Quarkus' classloader and the test by Surefire's,
    // so both sides must observe the same path.
    private static final String DIR = ".ajentic/test-memory";
    private static final Path FILE = Path.of(DIR, "incidents.jsonl");

    @BeforeEach
    void reset() throws java.io.IOException {
        WireMockLlmTestResource.server().resetRequests();
        if (Files.exists(FILE)) {
            Files.delete(FILE);
        }
    }

    @Test
    void episodeIsPersistedSearchableAndFedIntoNewSessions() throws java.io.IOException {
        given()
                .contentType("application/json")
                .body("{\"scenarioId\":\"orders-bad-deployment\",\"policy\":\"heuristic\",\"seed\":7}")
                .when().post("/api/v1/episodes/simulate")
                .then().statusCode(200)
                .body("solved", org.hamcrest.Matchers.is(true));

        // Persisted on disk.
        assertTrue(Files.exists(FILE), "incidents.jsonl should exist at " + FILE);
        String disk = Files.readString(FILE);
        assertTrue(disk.contains("orders-bad-deployment"), disk);

        // Searchable directly, newest first, filtered by service + fault kind.
        List<StoredIncident> matches = memory.search("", "orders-api", "BAD_DEPLOYMENT", 3);
        assertFalse(matches.isEmpty(), disk);
        assertTrue(matches.getFirst().solved());

        // A new session's system prompt carries the similar-past-incidents summary to the LLM.
        String sessionId = given()
                .contentType("application/json")
                .body("{\"scenarioId\":\"orders-bad-deployment\",\"seed\":1,\"model\":\"wm-orders\"}")
                .when().post("/api/v1/agent/sessions")
                .then().statusCode(201)
                .extract().path("id");
        given()
                .contentType("application/json")
                .body("{\"message\":\"Investigate the incident.\"}")
                .when().post("/api/v1/agent/sessions/" + sessionId + "/chat")
                .then().statusCode(200);

        String llmBody = WireMockLlmTestResource.server().getAllServeEvents().getFirst()
                .getRequest().getBodyAsString();
        assertTrue(llmBody.contains("Similar past incidents"), llmBody);
        assertTrue(llmBody.contains("orders-bad-deployment"), llmBody);
    }

    @Test
    void corruptLineIsSkippedNotFatal() throws java.io.IOException {
        given()
                .contentType("application/json")
                .body("{\"scenarioId\":\"orders-bad-deployment\",\"policy\":\"heuristic\",\"seed\":7}")
                .when().post("/api/v1/episodes/simulate")
                .then().statusCode(200);

        int before = memory.search("", "orders-api", "BAD_DEPLOYMENT", 3).size();
        Files.writeString(FILE, "this is not json\n", StandardOpenOption.APPEND);

        List<StoredIncident> after = memory.search("", "orders-api", "BAD_DEPLOYMENT", 3);
        assertTrue(after.size() >= before, "corrupt line must not hide valid incidents");
    }

    @Test
    void identifiersAreMaskedBeforePersistence() throws java.io.IOException {
        SimulationResult result = new SimulationResult(
                "mem-1",
                "orders-bad-deployment",
                true,
                true,
                true,
                1.34,
                6,
                TerminationReason.RESOLVED,
                List.of(
                        new StepRecord(0, "query METRICS for orders-api", 0.0, "noise", false),
                        new StepRecord(1, "declare 'contact admin@example.com for pool tuning' on orders-api",
                                0.5, "diagnosed", false),
                        new StepRecord(2, "apply ROLLBACK_DEPLOY to orders-api", 0.84, "resolved", true)));
        memory.save(result);

        String disk = Files.readString(FILE);
        assertFalse(disk.contains("admin@example.com"), disk);
        assertTrue(disk.contains("[[EMAIL_1]]"), disk);
        assertTrue(disk.contains("ROLLBACK_DEPLOY"), disk);
    }
}
