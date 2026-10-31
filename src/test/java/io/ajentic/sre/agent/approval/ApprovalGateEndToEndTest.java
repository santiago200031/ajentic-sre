package io.ajentic.sre.agent.approval;

import io.ajentic.sre.test.WireMockLlmTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The REQUIRE_APPROVAL mutation gate: mutating calls are deferred behind an operator decision, READ tools keep
 * working, and approve/deny settle the deferred call exactly once.
 */
@QuarkusTest
@WithTestResource(WireMockLlmTestResource.class)
@io.quarkus.test.junit.TestProfile(ApprovalGateEndToEndTest.GateProfile.class)
class ApprovalGateEndToEndTest {

    public static class GateProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("ajentic.tools.mutation-policy", "require-approval");
        }
    }

    @BeforeEach
    void resetScenarioState() {
        WireMockLlmTestResource.resetScenarios();
    }

    private String createSession() {
        return given()
                .contentType("application/json")
                .body("{\"scenarioId\":\"orders-bad-deployment\",\"seed\":1,\"model\":\"wm-approval\"}")
                .when().post("/api/v1/agent/sessions")
                .then().statusCode(201)
                .extract().path("id");
    }

    private void chat(String sessionId, String message) {
        given()
                .contentType("application/json")
                .body("{\"message\":\"" + message + "\"}")
                .when().post("/api/v1/agent/sessions/" + sessionId + "/chat")
                .then().statusCode(200)
                .body("answer", containsString("pending operator approval"));
    }

    private String pendingJson(String sessionId) {
        return given()
                .get("/api/v1/agent/sessions/" + sessionId + "/pending")
                .then().statusCode(200)
                .extract().body().asString();
    }

    private static String idOf(String pendingJson) {
        int start = pendingJson.indexOf("\"approvalId\":\"");
        int end = pendingJson.indexOf('"', start + "\"approvalId\":\"".length());
        return pendingJson.substring(start + "\"approvalId\":\"".length(), end);
    }

    private String approve(String sessionId, String approvalId, String decision, String note) {
        String body = "{\"approvalId\":\"" + approvalId + "\",\"decision\":\"" + decision + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
        return given()
                .contentType("application/json")
                .body(body)
                .when().post("/api/v1/agent/sessions/" + sessionId + "/approve")
                .then().statusCode(200)
                .extract().body().asString();
    }

    @Test
    void requireApprovalDefersTheMutatingCallWhileReadsContinue() {
        String sessionId = createSession();
        chat(sessionId, "Investigate the incident.");

        String pending = pendingJson(sessionId);
        assertTrue(pending.contains("\"toolName\":\"apply_remediation\""), pending);
        assertTrue(pending.contains("\"service\":\"orders-api\""), pending);
        assertTrue(pending.contains("\"approvalId\":\""), pending);
    }

    @Test
    void approveExecutesTheDeferredCallExactlyOnce() {
        String sessionId = createSession();
        chat(sessionId, "Investigate the incident.");
        String approvalId = idOf(pendingJson(sessionId));

        String first = approve(sessionId, approvalId, "approve", null);
        assertTrue(first.contains("\"approved\":true"), first);
        assertTrue(first.contains("\"executed\":true"), first);

        // Idempotent repeat: same stored outcome, no second execution.
        String repeat = approve(sessionId, approvalId, "approve", null);
        assertEquals(first, repeat);

        // The remediation landed exactly once: the environment is resolved.
        given().get("/api/v1/agent/sessions/" + sessionId)
                .then().statusCode(200)
                .body("observation.resolved", is(true));
    }

    @Test
    void denyNeverExecutesTheDeferredCall() {
        String sessionId = createSession();
        chat(sessionId, "Investigate the incident.");
        String approvalId = idOf(pendingJson(sessionId));

        String denied = approve(sessionId, approvalId, "deny", "too risky during peak hours");
        assertTrue(denied.contains("\"approved\":false"), denied);
        assertTrue(denied.contains("\"executed\":false"), denied);
        assertTrue(denied.contains("too risky during peak hours"), denied);

        given().get("/api/v1/agent/sessions/" + sessionId + "/pending")
                .then().statusCode(200)
                .body("size()", is(0));

        given().get("/api/v1/agent/sessions/" + sessionId)
                .then().statusCode(200)
                .body("observation.resolved", is(false));
    }

    @Test
    void unknownApprovalIdIsRejected() {
        String sessionId = createSession();
        chat(sessionId, "Investigate the incident.");

        given()
                .contentType("application/json")
                .body("{\"approvalId\":\"nope\",\"decision\":\"approve\"}")
                .when().post("/api/v1/agent/sessions/" + sessionId + "/approve")
                .then().statusCode(404);
    }
}
