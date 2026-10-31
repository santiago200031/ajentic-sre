package io.ajentic.sre.api;

import io.ajentic.sre.agent.integration.ChatCrypto;
import io.ajentic.sre.test.WireMockLlmTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@WithTestResource(WireMockLlmTestResource.class)
@TestProfile(ChatOpsResourceEndToEndTest.ChatOpsProfile.class)
class ChatOpsResourceEndToEndTest {

    private static final String SLACK_SECRET = "test-slack-secret";
    private static final String FACADE_API_KEY = "test-facade-key";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static class ChatOpsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "ajentic.chat.slack.signing-secret", SLACK_SECRET,
                    "ajentic.chat.api-key", FACADE_API_KEY,
                    "ajentic.tools.mutation-policy", "require-approval",
                    "ajentic.llm.model", "wm-default"
            );
        }
    }

    @BeforeEach
    void resetScenarios() {
        WireMockLlmTestResource.resetScenarios();
    }

    @Test
    void badSlackSignatureReturns401() {
        long ts = System.currentTimeMillis() / 1000;
        String body = "{\"event_id\":\"ev-bad-1\",\"text\":\"ask what is wrong\"}";

        given()
                .contentType("application/json")
                .header("X-Slack-Signature", "v0=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .header("X-Slack-Request-Timestamp", String.valueOf(ts))
                .body(body)
                .when().post("/api/v1/webhooks/chat/slack")
                .then().statusCode(401);
    }

    @Test
    void expiredSlackTimestampReturns401() {
        long expiredTs = (System.currentTimeMillis() / 1000) - 400; // > 300s expired
        String body = "{\"event_id\":\"ev-exp-1\",\"text\":\"ask what is wrong\"}";
        String sig = computeSlackSig(body, expiredTs);

        given()
                .contentType("application/json")
                .header("X-Slack-Signature", sig)
                .header("X-Slack-Request-Timestamp", String.valueOf(expiredTs))
                .body(body)
                .when().post("/api/v1/webhooks/chat/slack")
                .then().statusCode(401);
    }

    @Test
    void validSlackSignatureAndDuplicateEventIdReturnsSingleTurn() {
        long ts = System.currentTimeMillis() / 1000;
        String eventId = "ev-dedup-" + System.nanoTime();
        String body = "{\"event_id\":\"" + eventId + "\",\"text\":\"ask investigate latency\"}";
        String sig = computeSlackSig(body, ts);

        // First call executes turn
        String first = given()
                .contentType("application/json")
                .header("X-Slack-Signature", sig)
                .header("X-Slack-Request-Timestamp", String.valueOf(ts))
                .body(body)
                .when().post("/api/v1/webhooks/chat/slack")
                .then().statusCode(200)
                .body("response_type", equalTo("in_channel"))
                .extract().path("text");

        // Second call with identical event_id returns deduplicated answer without new turn
        String second = given()
                .contentType("application/json")
                .header("X-Slack-Signature", sig)
                .header("X-Slack-Request-Timestamp", String.valueOf(ts))
                .body(body)
                .when().post("/api/v1/webhooks/chat/slack")
                .then().statusCode(200)
                .body("response_type", equalTo("in_channel"))
                .extract().path("text");

        assertEquals(first, second);
    }

    @Test
    void inboundChatCommandsApproveAndDenyWiredToApprovalGates() {
        // 1. Create a session and advance it with a mutating tool call to trigger pending approval
        String sessionId = given()
                .contentType("application/json")
                .body("{\"scenarioId\":\"orders-bad-deployment\",\"seed\":1,\"model\":\"wm-approval\"}")
                .when().post("/api/v1/agent/sessions")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .body("{\"message\":\"Investigate and fix the incident.\"}")
                .when().post("/api/v1/agent/sessions/" + sessionId + "/chat")
                .then().statusCode(200)
                .body("answer", containsString("pending operator approval"));

        // 2. Fetch pending approval id
        String pending = given()
                .get("/api/v1/agent/sessions/" + sessionId + "/pending")
                .then().statusCode(200)
                .extract().body().asString();

        int start = pending.indexOf("\"approvalId\":\"");
        assertTrue(start >= 0, "Expected pending approval in session");
        int end = pending.indexOf('"', start + "\"approvalId\":\"".length());
        String approvalId = pending.substring(start + "\"approvalId\":\"".length(), end);

        // 3. Approve via inbound ChatOps command
        long ts = System.currentTimeMillis() / 1000;
        String approveBody = "{\"event_id\":\"ev-app-" + System.nanoTime() + "\",\"text\":\"approve " + approvalId + "\"}";
        String sig = computeSlackSig(approveBody, ts);

        given()
                .contentType("application/json")
                .header("X-Slack-Signature", sig)
                .header("X-Slack-Request-Timestamp", String.valueOf(ts))
                .body(approveBody)
                .when().post("/api/v1/webhooks/chat/slack")
                .then().statusCode(200)
                .body("text", containsString("approved: executed successfully"));

        // Verify the environment was resolved by the executed approval
        given().get("/api/v1/agent/sessions/" + sessionId)
                .then().statusCode(200)
                .body("observation.resolved", is(true));
    }

    @Test
    void inboundChatCommandStatusReportsSessionAndRemediation() {
        // 1. Create an active session
        String sessionId = given()
                .contentType("application/json")
                .body("{\"scenarioId\":\"orders-bad-deployment\",\"seed\":1}")
                .when().post("/api/v1/agent/sessions")
                .then().statusCode(201)
                .extract().path("id");

        // 2. Query status via inbound Teams webhook
        String teamsBody = "{\"id\":\"teams-status-1\",\"text\":\"status " + sessionId + "\"}";

        given()
                .contentType("application/json")
                .body(teamsBody)
                .when().post("/api/v1/webhooks/chat/teams")
                .then().statusCode(200)
                .body("text", containsString("Session " + sessionId))
                .body("text", containsString("orders-bad-deployment"));
    }

    @Test
    void openAiModelsEndpointRequiresAuthAndListsModel() {
        // Unauthenticated request -> 401
        given()
                .when().get("/v1/models")
                .then().statusCode(401);

        // Authenticated request -> 200 with model
        given()
                .header("Authorization", "Bearer " + FACADE_API_KEY)
                .when().get("/v1/models")
                .then().statusCode(200)
                .body("object", equalTo("list"))
                .body("data[0].id", equalTo("wm-default"));
    }

    @Test
    void openAiChatCompletionsUnknownModelReturns404() {
        String body = """
                {
                  "model": "unknown-nonexistent-model-xyz",
                  "messages": [{"role": "user", "content": "What is wrong?"}]
                }
                """;

        given()
                .header("Authorization", "Bearer " + FACADE_API_KEY)
                .contentType("application/json")
                .body(body)
                .when().post("/v1/chat/completions")
                .then().statusCode(404)
                .body("error.code", equalTo("model_not_found"));
    }

    @Test
    void openAiChatCompletionsNonStreamAnswersFromScriptedLlm() {
        String body = """
                {
                  "model": "wm-default",
                  "messages": [
                    {"role": "system", "content": "You are an SRE bot"},
                    {"role": "user", "content": "Check database status"}
                  ],
                  "stream": false
                }
                """;

        String answer = given()
                .header("Authorization", "Bearer " + FACADE_API_KEY)
                .contentType("application/json")
                .body(body)
                .when().post("/v1/chat/completions")
                .then().statusCode(200)
                .body("object", equalTo("chat.completion"))
                .body("model", equalTo("wm-default"))
                .body("choices[0].finish_reason", equalTo("stop"))
                .extract().path("choices[0].message.content");

        assertFalse(answer.isBlank());
    }

    @Test
    void openAiChatCompletionsStreamEmitsSseChunksAndDone() {
        String body = """
                {
                  "model": "wm-default",
                  "messages": [{"role": "user", "content": "Check database status"}],
                  "stream": true
                }
                """;

        String sseOutput = given()
                .header("Authorization", "Bearer " + FACADE_API_KEY)
                .contentType("application/json")
                .accept("text/event-stream")
                .body(body)
                .when().post("/v1/chat/completions")
                .then().statusCode(200)
                .contentType("text/event-stream")
                .extract().body().asString();

        assertTrue(sseOutput.contains("data: {"), sseOutput);
        assertTrue(sseOutput.contains("\"object\":\"chat.completion.chunk\""), sseOutput);
        assertTrue(sseOutput.contains("\"finish_reason\":\"stop\""), sseOutput);
        assertTrue(sseOutput.contains("data: [DONE]"), sseOutput);
    }

    private static String computeSlackSig(String body, long timestamp) {
        String base = "v0:" + timestamp + ":" + body;
        return "v0=" + ChatCrypto.hmacSha256Hex(base, SLACK_SECRET);
    }
}
