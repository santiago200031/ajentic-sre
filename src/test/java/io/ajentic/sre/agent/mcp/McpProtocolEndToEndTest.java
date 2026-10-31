package io.ajentic.sre.agent.mcp;

import io.quarkiverse.mcp.server.PromptManager;
import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end tests for Quarkus MCP Server features (Tools, Prompts, Resources) and HTTP transport.
 */
@QuarkusTest
class McpProtocolEndToEndTest {

    @Inject
    ToolManager toolManager;

    @Inject
    ResourceManager resourceManager;

    @Inject
    PromptManager promptManager;

    @Test
    void answersInitializeOverHttp() {
        given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("""
                        {
                          "jsonrpc": "2.0",
                          "id": 1,
                          "method": "initialize",
                          "params": {
                            "protocolVersion": "2024-11-05",
                            "capabilities": {},
                            "clientInfo": {"name": "test-client", "version": "1.0"}
                          }
                        }
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.serverInfo.name", is("ajentic-sre"))
                .body("result.protocolVersion", notNullValue());
    }

    @Test
    void exposesAllSreToolsViaToolManager() {
        assertNotNull(toolManager.getTool("query_telemetry"));
        assertNotNull(toolManager.getTool("inspect_recent_changes"));
        assertNotNull(toolManager.getTool("correlate_anomalies"));
        assertNotNull(toolManager.getTool("list_remediations"));
        assertNotNull(toolManager.getTool("apply_remediation"));
        assertNotNull(toolManager.getTool("read_runbook"));
        assertNotNull(toolManager.getTool("declare_diagnosis"));
        assertNotNull(toolManager.getTool("list_scenarios"));
        assertNotNull(toolManager.getTool("start_episode"));
        assertNotNull(toolManager.getTool("run_incident_drill"));
    }

    @Test
    void exposesResourcesViaResourceManager() {
        assertNotNull(resourceManager.getResource("sre://scenarios/checkout-latency-db-pool"));
        assertNotNull(resourceManager.getResource("sre://runbooks/checkout-latency-db-pool/rb-db-pool"));
        assertNotNull(resourceManager.getResource("sre://topology/checkout-latency-db-pool"));
    }

    @Test
    void exposesPromptsViaPromptManager() {
        assertNotNull(promptManager.getPrompt("investigate-incident"));
        assertNotNull(promptManager.getPrompt("summarize-postmortem"));
    }

    @Test
    void listsAndCallsToolsOverHttp() {
        String sessionId = initSession();

        given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body("""
                        {
                          "jsonrpc": "2.0",
                          "id": 2,
                          "method": "tools/list",
                          "params": {}
                        }
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.tools.size()", org.hamcrest.Matchers.greaterThanOrEqualTo(9));

        given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body("""
                        {
                          "jsonrpc": "2.0",
                          "id": 3,
                          "method": "tools/call",
                          "params": {
                            "name": "list_scenarios",
                            "arguments": {}
                          }
                        }
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", org.hamcrest.Matchers.containsString("checkout-latency-db-pool"));
    }

    @Test
    void readsResourcesOverHttp() {
        String sessionId = initSession();

        given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body("""
                        {
                          "jsonrpc": "2.0",
                          "id": 2,
                          "method": "resources/read",
                          "params": {
                            "uri": "sre://scenarios/checkout-latency-db-pool"
                          }
                        }
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.contents[0].mimeType", is("application/json"))
                .body("result.contents[0].text", org.hamcrest.Matchers.containsString("checkout-latency-db-pool"));
    }

    @Test
    void getsPromptsOverHttp() {
        String sessionId = initSession();

        given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body("""
                        {
                          "jsonrpc": "2.0",
                          "id": 2,
                          "method": "prompts/get",
                          "params": {
                            "name": "investigate-incident",
                            "arguments": {
                              "scenarioId": "checkout-latency-db-pool"
                            }
                          }
                        }
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.messages[0].content.text", org.hamcrest.Matchers.containsString("Investigate the 'checkout-latency-db-pool' incident"));
    }

    @Test
    void recoversFromMalformedJsonWithParseError() {
        given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("{ this is not json")
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("error.code", is(-32700));
    }

    private String initSession() {
        return given()
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("""
                        {
                          "jsonrpc": "2.0",
                          "id": 1,
                          "method": "initialize",
                          "params": {
                            "protocolVersion": "2024-11-05",
                            "capabilities": {},
                            "clientInfo": {"name": "test-client", "version": "1.0"}
                          }
                        }
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .extract().header("Mcp-Session-Id");
    }
}
