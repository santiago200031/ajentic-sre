package io.ajentic.sre.api;

import io.ajentic.sre.test.WireMockLlmTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
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
                .body("sessionId", is(sessionId))
                .body("usage.totalTokens", greaterThan(0));

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
                .header("Accept", "application/json, text/event-stream")
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "test-client", "version", "1.0"))))
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.protocolVersion", not(blankOrNullString()))
                .body("result.serverInfo.name", is("ajentic-sre"));
    }

    @Test
    void listsAndCallsMcpToolsOverHttp() {
        String sessionId = given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "test-client", "version", "1.0"))))
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .extract().header("Mcp-Session-Id");

        given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of()))
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.tools.size()", greaterThanOrEqualTo(6));

        given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "id", 3,
                        "method", "tools/call",
                        "params", Map.of(
                                "name", "list_scenarios",
                                "arguments", Map.of())))
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", containsString("checkout-latency-db-pool"));
    }

    @Test
    void readsMcpResourcesOverHttp() {
        String sessionId = given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "test-client", "version", "1.0"))))
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .extract().header("Mcp-Session-Id");

        given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "id", 9,
                        "method", "resources/read",
                        "params", Map.of("uri", "sre://scenarios/checkout-latency-db-pool")))
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.contents[0].mimeType", is("application/json"))
                .body("result.contents[0].text", containsString("checkout-latency-db-pool"));
    }

    @Test
    void recoversFromMalformedMcpJson() {
        given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("{ this is not json")
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("error.code", is(-32700));
    }

    @Test
    void redirectsRootToSwaggerUi() {
        given()
                .redirects().follow(false)
                .when().get("/")
                .then()
                .statusCode(303)
                .header("Location", containsString("/q/swagger-ui"));
    }

    @Test
    void servesSwaggerUiAndOpenApiSpec() {
        given()
                .when().get("/q/swagger-ui/")
                .then()
                .statusCode(200);

        given()
                .when().get("/q/openapi")
                .then()
                .statusCode(200)
                .body(containsString("Ajentic SRE"));
    }

    @Test
    void answersOneShotInvestigationWithoutRetainedSession() {
        given()
                .contentType("application/json")
                .body(Map.of(
                        "scenarioId", "checkout-latency-db-pool",
                        "question", "Why is checkout slow?",
                        "model", "wm-api-chat"))
                .when().post("/api/v1/agent/ask")
                .then()
                .statusCode(200)
                .body("answer", not(blankOrNullString()))
                .body("usage.totalTokens", greaterThan(0));
    }

    @Test
    void runsOneShotSimulationWithoutRetainedEpisode() {
        given()
                .contentType("application/json")
                .body(Map.of(
                        "scenarioId", "checkout-latency-db-pool",
                        "policy", "heuristic"))
                .when().post("/api/v1/episodes/simulate")
                .then()
                .statusCode(200)
                .body("scenarioId", is("checkout-latency-db-pool"))
                .body("solved", is(true));
    }

    @Test
    void warningAlertUnderMinSeverityErrorIsIgnoredWith202() {
        given()
                .contentType("application/json")
                .body(Map.of(
                        "severity", "WARNING",
                        "repository", "acme/checkout-service",
                        "message", "Minor latency increase"))
                .when().post("/api/v1/webhooks/alerts")
                .then()
                .statusCode(202)
                .body("status", is("IGNORED"))
                .body("severity", is("WARNING"))
                .body("repository", is("acme/checkout-service"));
    }

    @Test
    void missingRepositoryWithoutDefaultIsIgnoredWith202() {
        given()
                .contentType("application/json")
                .body(Map.of(
                        "severity", "ERROR",
                        "message", "Database timeout with unknown repo"))
                .when().post("/api/v1/webhooks/alerts")
                .then()
                .statusCode(202)
                .body("status", is("IGNORED"))
                .body("severity", is("ERROR"));
    }

    @Test
    void fullStubbedAlertRemediationRunReturns200WithPullRequestUrl() {
        given()
                .contentType("application/json")
                .body(Map.of(
                        "severity", "ERROR",
                        "repository", "acme/checkout-service",
                        "title", "NullPointerException in checkout",
                        "message", "NullPointerException: order cannot be null",
                        "stackTrace", "java.lang.NullPointerException at com.acme.Checkout.process"))
                .when().post("/api/v1/webhooks/alerts")
                .then()
                .statusCode(200)
                .body("status", is("PROCESSED"))
                .body("severity", is("ERROR"))
                .body("repository", is("acme/checkout-service"))
                .body("answer", containsString("https://github.com/acme/checkout-service/pull/42"))
                .body("sessionId", not(blankOrNullString()));
    }

    @Test
    void multiCloudAlertShapesAcceptedAtAlertWebhookEndpoint() {
        // CloudWatch-shaped
        given()
                .contentType("application/json")
                .body(Map.of(
                        "NewStateValue", "ALARM",
                        "AlarmName", "HighErrorRate-checkout",
                        "NewStateReason", "5xx spike",
                        "AlarmDescription", "java.lang.NullPointerException",
                        "repository", "acme/checkout-service"))
                .when().post("/api/v1/webhooks/alerts")
                .then()
                .statusCode(200)
                .body("status", is("PROCESSED"));

        // Azure-shaped
        given()
                .contentType("application/json")
                .body(Map.of(
                        "data", Map.of(
                                "essentials", Map.of(
                                        "alertRule", "High Latency",
                                        "description", "5xx spike",
                                        "severity", "Sev1",
                                        "repo", "acme/checkout-service"),
                                "alertContext", "trace")))
                .when().post("/api/v1/webhooks/alerts")
                .then()
                .statusCode(200)
                .body("status", is("PROCESSED"));

        // Alertmanager-shaped
        given()
                .contentType("application/json")
                .body(Map.of(
                        "alerts", List.of(Map.of(
                                "labels", Map.of(
                                        "alertname", "KubePodCrashLooping",
                                        "severity", "error",
                                        "github_repo", "acme/checkout-service"),
                                "annotations", Map.of(
                                        "summary", "Crash looping",
                                        "stackTrace", "trace")))))
                .when().post("/api/v1/webhooks/alerts")
                .then()
                .statusCode(200)
                .body("status", is("PROCESSED"));
    }
}
