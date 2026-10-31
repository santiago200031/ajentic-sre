package io.ajentic.sre.api;

import io.ajentic.sre.test.WireMockLlmTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streams one scripted ReAct turn over SSE: events arrive in ReAct order (usage, thought, tool_call,
 * tool_result, ..., done) and the terminal event carries the final answer and cumulative usage.
 */
@QuarkusTest
@WithTestResource(WireMockLlmTestResource.class)
class SseChatStreamEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void resetWireMock() {
        WireMockLlmTestResource.resetScenarios();
    }

    @Test
    void streamsReActEventsInOrderAndFinishesWithDone() {
        String sessionId = given()
                .contentType("application/json")
                .body(Map.of("scenarioId", "orders-bad-deployment", "seed", 1L, "model", "wm-stream"))
                .when().post("/api/v1/agent/sessions")
                .then()
                .statusCode(201)
                .extract().path("id");

        String body = given()
                .contentType("application/json")
                .accept("text/event-stream")
                .body(Map.of("message", "Investigate the incident."))
                .when().post("/api/v1/agent/sessions/" + sessionId + "/chat/stream")
                .then()
                .statusCode(200)
                .contentType("text/event-stream")
                .extract().body().asString();

        List<String[]> events = parseSse(body);
        List<String> types = events.stream().map(event -> event[0]).toList();
        assertEquals(List.of("usage", "thought", "tool_call", "tool_result", "usage", "done"), types);

        JsonNode toolCall = MAPPER.readTree(new StringReader(events.get(2)[1]));
        assertEquals("query_telemetry", toolCall.get("name").asText());
        assertEquals("orders-api", toolCall.get("arguments").get("service").asText());
        assertTrue(events.get(3)[1].length() > 0);

        JsonNode done = MAPPER.readTree(new StringReader(events.get(5)[1]));
        assertTrue(done.get("answer").asText().contains("restarting orders-api"), done.get("answer").asText());
        assertEquals(115, done.get("totalTokens").asInt());
    }

    @Test
    void upstreamFailureEmitsAnErrorEventAndClosesTheStream() {
        String sessionId = given()
                .contentType("application/json")
                .body(Map.of("scenarioId", "orders-bad-deployment", "seed", 1L, "model", "wm-stream-error"))
                .when().post("/api/v1/agent/sessions")
                .then()
                .statusCode(201)
                .extract().path("id");

        String body = given()
                .contentType("application/json")
                .accept("text/event-stream")
                .body(Map.of("message", "Investigate the incident."))
                .when().post("/api/v1/agent/sessions/" + sessionId + "/chat/stream")
                .then()
                .statusCode(200)
                .contentType("text/event-stream")
                .extract().body().asString();

        List<String[]> events = parseSse(body);
        assertEquals(1, events.size());
        assertEquals("error", events.getFirst()[0]);
        assertTrue(events.getFirst()[1].contains("LLM request failed"), events.getFirst()[1]);
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
