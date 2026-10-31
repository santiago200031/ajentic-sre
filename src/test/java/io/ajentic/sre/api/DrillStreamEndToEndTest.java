package io.ajentic.sre.api;

import io.quarkus.test.junit.QuarkusTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the drill SSE stream: a heuristic drill runs offline and its steps stream as server-sent
 * events before the final done event.
 */
@QuarkusTest
class DrillStreamEndToEndTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void streamEmitsStepsAndDoneForHeuristicDrill() {
        String body = given()
                .contentType("application/json")
                .accept("text/event-stream")
                .body("{\"scenarioId\":\"orders-bad-deployment\"}")
                .when().post("/api/v1/drills/stream")
                .then()
                .statusCode(200)
                .contentType("text/event-stream")
                .extract().body().asString();

        List<String[]> events = parseSse(body);
        assertTrue(events.size() >= 3, "expected start, steps, and done events but got " + events);

        assertEquals("start", events.get(0)[0]);
        assertEquals("done", events.getLast()[0]);

        List<String> stepTypes = events.subList(1, events.size() - 1).stream().map(e -> e[0]).toList();
        assertFalse(stepTypes.isEmpty());
        assertTrue(stepTypes.stream().allMatch("step"::equals), stepTypes.toString());

        JsonNode done = mapper.readTree(events.getLast()[1]);
        assertTrue(done.path("solved").asBoolean(), done.toString());
        assertTrue(done.path("resolved").asBoolean(), done.toString());
        assertTrue(done.path("steps").asInt() >= 1, done.toString());
        assertEquals("RESOLVED", done.path("terminationReason").asText(), done.toString());
    }

    @Test
    void unknownScenarioFailsBeforeStreaming() {
        given()
                .contentType("application/json")
                .accept("text/event-stream")
                .body("{\"scenarioId\":\"nope\"}")
                .when().post("/api/v1/drills/stream")
                .then()
                .statusCode(404);
    }

    private static List<String[]> parseSse(String body) {
        List<String[]> events = new ArrayList<>();
        String type = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.startsWith("event:")) {
                type = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).stripLeading());
            } else if (line.isEmpty() && type != null) {
                events.add(new String[] { type, data.toString() });
                type = null;
                data.setLength(0);
            }
        }
        if (type != null) {
            events.add(new String[] { type, data.toString() });
        }
        return events;
    }
}
