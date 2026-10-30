package io.ajentic.sre.api;

import io.ajentic.sre.test.WireMockLlmTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;

@QuarkusTest
@WithTestResource(WireMockLlmTestResource.class)
class ApiEndToEndTest {

    @BeforeEach
    void resetWireMock() {
        WireMockLlmTestResource.resetScenarios();
    }

    @Test
    void listsTheScenarioLibrary() {
        given()
                .when().get("/api/v1/scenarios")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(6));
    }

    @Test
    void exposesTheToolCatalog() {
        given()
                .when().get("/api/v1/tools")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(6));
    }

    @Test
    void runsAnEpisodeToResolutionThroughTheApi() {
        String episodeId = given()
                .contentType("application/json")
                .body(Map.of("scenarioId", "payments-memory-leak"))
                .when().post("/api/v1/episodes")
                .then()
                .statusCode(201)
                .body("observation.scenarioId", is("payments-memory-leak"))
                .extract().path("id");

        given()
                .contentType("application/json")
                .body(Map.of("policy", "heuristic"))
                .when().post("/api/v1/episodes/" + episodeId + "/run")
                .then()
                .statusCode(200)
                .body("solved", is(true))
                .body("diagnosed", is(true))
                .body("resolved", is(true));

        given()
                .when().get("/q/metrics")
                .then()
                .statusCode(200)
                .body(containsString("ajentic_episodes_started_total"))
                .body(containsString("ajentic_episodes_finished_total"));
    }

    @Test
    void stepsThroughInvestigationManually() {
        String episodeId = given()
                .contentType("application/json")
                .body(Map.of("scenarioId", "search-dns-failure"))
                .when().post("/api/v1/episodes")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .body(Map.of("type", "query_telemetry", "signal", "LOGS", "service", "search-api"))
                .when().post("/api/v1/episodes/" + episodeId + "/actions")
                .then()
                .statusCode(200)
                .body("observation.signals.size()", greaterThanOrEqualTo(1));
    }

    @Test
    void rejectsUnknownScenarioWithNotFound() {
        given()
                .contentType("application/json")
                .body(Map.of("scenarioId", "does-not-exist"))
                .when().post("/api/v1/episodes")
                .then()
                .statusCode(404);
    }

    @Test
    void rejectsMalformedActionWithBadRequest() {
        String episodeId = given()
                .contentType("application/json")
                .body(Map.of("scenarioId", "auth-certificate-expiry"))
                .when().post("/api/v1/episodes")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .body(Map.of("type", "query_telemetry"))
                .when().post("/api/v1/episodes/" + episodeId + "/actions")
                .then()
                .statusCode(400);
    }

    @Test
    void createsAnAgentSessionAndAnswersAChatTurn() {
        String sessionId = given()
                .contentType("application/json")
                .body(Map.of("scenarioId", "checkout-latency-db-pool", "model", "wm-api-chat"))
                .when().post("/api/v1/agent/sessions")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .body(Map.of("message", "Why is checkout slow?"))
                .when().post("/api/v1/agent/sessions/" + sessionId + "/chat")
                .then()
                .statusCode(200)
                .body("answer", not(blankOrNullString()))
                .body("sessionId", is(sessionId));

        given()
                .when().get("/q/metrics")
                .then()
                .statusCode(200)
                .body(containsString("ajentic_llm_tokens_total"));
    }

    @Test
    void runsScheduledIncidentDrills() {
        String scheduleId = given()
                .contentType("application/json")
                .body(Map.of(
                        "name", "drill-" + UUID.randomUUID(),
                        "cron", "0 3 * * *",
                        "scenarioId", "orders-bad-deployment"))
                .when().post("/api/v1/schedules")
                .then()
                .statusCode(201)
                .body("loopGroupId", not(blankOrNullString()))
                .extract().path("id");

        given()
                .when().post("/api/v1/schedules/" + scheduleId + "/run")
                .then()
                .statusCode(200)
                .body("solved", is(true));

        given()
                .when().get("/api/v1/schedules/drills")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));
    }

    @Test
    void rejectsDuplicateSchedules() {
        Map<String, Object> schedule = Map.of(
                "name", "duplicate-" + UUID.randomUUID(),
                "cron", "0 4 * * *",
                "scenarioId", "orders-bad-deployment");

        given().contentType("application/json").body(schedule)
                .when().post("/api/v1/schedules").then().statusCode(201);
        given().contentType("application/json").body(schedule)
                .when().post("/api/v1/schedules").then().statusCode(409);
    }

    @Test
    void initializesMcpOverHttp() {
        given()
                .contentType("application/json")
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of()))
                .when().post("/api/v1/mcp")
                .then()
                .statusCode(200)
                .body("result.protocolVersion", not(blankOrNullString()))
                .body("result.serverInfo.name", is("ajentic-sre"));
    }

    @Test
    void listsAndCallsMcpToolsOverHttp() {
        given()
                .contentType("application/json")
                .body(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"))
                .when().post("/api/v1/mcp")
                .then()
                .statusCode(200)
                .body("result.tools.size()", greaterThanOrEqualTo(6));

        given()
                .contentType("application/json")
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "id", 3,
                        "method", "tools/call",
                        "params", Map.of(
                                "name", "query_telemetry",
                                "arguments", Map.of("signal", "LOGS", "service", "checkout-api"))))
                .when().post("/api/v1/mcp")
                .then()
                .statusCode(200)
                .body("result.isError", is(false))
                .body("result.content[0].text", not(blankOrNullString()));
    }

    @Test
    void acceptsMcpNotificationsWithNoContent() {
        given()
                .contentType("application/json")
                .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                .when().post("/api/v1/mcp")
                .then()
                .statusCode(202);
    }

    @Test
    void describesMcpOverHttpGet() {
        given()
                .when().get("/api/v1/mcp")
                .then()
                .statusCode(200)
                .body("server", is("ajentic-sre"))
                .body("protocolVersion", not(blankOrNullString()))
                .body("capabilities", hasItems("tools", "resources", "prompts"));
    }

    @Test
    void resetsTheActiveEpisodeOverHttpDelete() {
        given()
                .when().delete("/api/v1/mcp")
                .then()
                .statusCode(204);
    }

    @Test
    void handlesMcpBatchesOverHttp() {
        given()
                .contentType("application/json")
                .body("["
                        + "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                        + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}]")
                .when().post("/api/v1/mcp")
                .then()
                .statusCode(200)
                .body("size()", is(2))
                .body("[1].result.tools.size()", greaterThanOrEqualTo(9));
    }

    @Test
    void recoversFromMalformedMcpJson() {
        given()
                .contentType("application/json")
                .body("{ this is not json")
                .when().post("/api/v1/mcp")
                .then()
                .statusCode(200)
                .body("error.code", is(-32700));
    }

    @Test
    void readsMcpResourcesOverHttp() {
        given()
                .contentType("application/json")
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "id", 9,
                        "method", "resources/read",
                        "params", Map.of("uri", "sre://scenarios/checkout-latency-db-pool")))
                .when().post("/api/v1/mcp")
                .then()
                .statusCode(200)
                .body("result.contents[0].mimeType", is("application/json"));
    }
}
