package io.ajentic.sre.agent.approval;

import io.ajentic.sre.test.WireMockLlmTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expired approvals auto-deny and never execute the deferred mutating call, even if the operator approves late.
 */
@QuarkusTest
@WithTestResource(WireMockLlmTestResource.class)
@io.quarkus.test.junit.TestProfile(ApprovalExpiryEndToEndTest.ShortTtlProfile.class)
class ApprovalExpiryEndToEndTest {

    public static class ShortTtlProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "ajentic.tools.mutation-policy", "require-approval",
                    "ajentic.approval.ttl", "1s");
        }
    }

    @BeforeEach
    void resetScenarioState() {
        WireMockLlmTestResource.resetScenarios();
    }

    @Test
    void expiredApprovalNeverExecutesTheDeferredCall() throws InterruptedException {
        String sessionId = given()
                .contentType("application/json")
                .body("{\"scenarioId\":\"orders-bad-deployment\",\"seed\":1,\"model\":\"wm-approval\"}")
                .when().post("/api/v1/agent/sessions")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .body("{\"message\":\"Investigate the incident.\"}")
                .when().post("/api/v1/agent/sessions/" + sessionId + "/chat")
                .then().statusCode(200);

        // Capture the approval id before it expires.
        String pending = given()
                .get("/api/v1/agent/sessions/" + sessionId + "/pending")
                .then().statusCode(200)
                .extract().body().asString();
        int start = pending.indexOf("\"approvalId\":\"");
        String approvalId = pending.substring(start + "\"approvalId\":\"".length(),
                pending.indexOf('"', start + "\"approvalId\":\"".length()));

        Thread.sleep(1500);

        // The approval expired on its own.
        given().get("/api/v1/agent/sessions/" + sessionId + "/pending")
                .then().statusCode(200)
                .body("size()", is(0));

        // A late approval cannot execute the call.
        String late = given()
                .contentType("application/json")
                .body("{\"approvalId\":\"" + approvalId + "\",\"decision\":\"approve\"}")
                .when().post("/api/v1/agent/sessions/" + sessionId + "/approve")
                .then().statusCode(200)
                .extract().body().asString();
        assertTrue(late.contains("\"executed\":false"), late);
        assertTrue(late.contains("expired"), late);

        given().get("/api/v1/agent/sessions/" + sessionId)
                .then().statusCode(200)
                .body("observation.resolved", is(false));
    }
}
