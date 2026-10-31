package io.ajentic.sre.agent.mcp;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the exact entry point the {@code ajentic-sre mcp} stdio command uses, so the newline-delimited JSON-RPC
 * framing is covered without having to script stdin. Resources, prompts, batches and lifecycle tools all flow through
 * the same handler.
 */
@QuarkusTest
class McpProtocolEndToEndTest {

    @Inject
    McpProtocolHandler handler;

    @Test
    void answersInitializeAndToolsListFrames() {
        String initialize = handler.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
        assertTrue(initialize.contains(McpProtocolHandler.PROTOCOL_VERSION), initialize);
        assertTrue(initialize.contains("ajentic-sre"), initialize);

        String tools = handler.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertTrue(tools.contains("query_telemetry"), tools);
        assertTrue(tools.contains("inputSchema"), tools);
        assertTrue(tools.contains("list_scenarios"), tools);
        assertTrue(tools.contains("start_episode"), tools);
        assertTrue(tools.contains("run_incident_drill"), tools);
    }

    @Test
    void executesToolCallsAndSkipsNotifications() {
        String call = handler.handle("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":
                {"name":"query_telemetry","arguments":{"signal":"LOGS","service":"checkout-api"}}}""");
        assertTrue(call.contains("\"isError\":false"), call);
        assertTrue(call.contains("\"content\""), call);

        String unknown = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}");
        assertTrue(unknown.contains("\"isError\":true"), unknown);

        assertNull(handler.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    }

    @Test
    void listsAndReadsResources() {
        String list = handler.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}");
        assertTrue(list.contains("sre://scenarios/checkout-latency-db-pool"), list);
        assertTrue(list.contains("sre://runbooks/checkout-latency-db-pool/rb-db-pool"), list);

        String scenario = handler.handle("""
                {"jsonrpc":"2.0","id":2,"method":"resources/read","params":
                {"uri":"sre://scenarios/checkout-latency-db-pool"}}""");
        assertTrue(scenario.contains("\"mimeType\":\"application/json\""), scenario);
        assertTrue(scenario.contains("checkout-latency-db-pool"), scenario);

        String runbook = handler.handle("""
                {"jsonrpc":"2.0","id":3,"method":"resources/read","params":
                {"uri":"sre://runbooks/checkout-latency-db-pool/rb-db-pool"}}""");
        assertTrue(runbook.contains("Database connection pool saturation"), runbook);

        String unknown = handler.handle("""
                {"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"sre://nope"}}""");
        assertTrue(unknown.contains("-32602"), unknown);
    }

    @Test
    void listsAndReadsTopologyResources() {
        String list = handler.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}");
        assertTrue(list.contains("sre://topology/checkout-latency-db-pool"), list);

        String topology = handler.handle("""
                {"jsonrpc":"2.0","id":2,"method":"resources/read","params":
                {"uri":"sre://topology/checkout-latency-db-pool"}}""");
        assertTrue(topology.contains("\"mimeType\":\"application/json\""), topology);
        assertTrue(topology.contains("checkout-api"), topology);
        assertTrue(topology.contains("orders-db"), topology);
    }

    @Test
    void callsTheTopologyTool() {
        String call = handler.handle("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":
                {"name":"get_service_topology","arguments":{}}}""");
        assertTrue(call.contains("\"isError\":false"), call);
        assertTrue(call.contains("edge-gateway -> checkout-api"), call);
        assertTrue(call.contains("checkout-api -> orders-db"), call);
    }

    @Test
    void listsAndRendersPrompts() {
        String list = handler.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"prompts/list\"}");
        assertTrue(list.contains("investigate-incident"), list);
        assertTrue(list.contains("summarize-postmortem"), list);

        String prompt = handler.handle("""
                {"jsonrpc":"2.0","id":2,"method":"prompts/get","params":
                {"name":"investigate-incident","arguments":{"scenarioId":"checkout-latency-db-pool"}}}""");
        assertTrue(prompt.contains("Investigate the 'checkout-latency-db-pool' incident"), prompt);

        String missing = handler.handle("""
                {"jsonrpc":"2.0","id":3,"method":"prompts/get","params":{"name":"investigate-incident"}}""");
        assertTrue(missing.contains("-32602"), missing);
    }

    @Test
    void handlesJsonRpcBatches() {
        String batch = handler.handle("""
                [{"jsonrpc":"2.0","id":1,"method":"ping"},
                 {"jsonrpc":"2.0","id":2,"method":"tools/list"},
                 {"jsonrpc":"2.0","method":"notifications/initialized"}]""");
        assertTrue(batch.startsWith("["), batch);
        assertTrue(batch.contains("\"id\":1"), batch);
        assertTrue(batch.contains("\"id\":2"), batch);
        assertTrue(batch.contains("query_telemetry"), batch);
    }

    @Test
    void keepsActiveEpisodesIsolatedPerMcpSession() {
        assertTrue(handler.handle("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":
                {"name":"start_episode","arguments":{"scenarioId":"checkout-latency-db-pool"}}}""", "client-a")
                .contains("episodeId"));
        assertTrue(handler.handle("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":
                {"name":"start_episode","arguments":{"scenarioId":"payments-memory-leak"}}}""", "client-b")
                .contains("episodeId"));

        String checkout = handler.handle("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":
                {"name":"query_telemetry","arguments":{"signal":"LOGS","service":"orders-db"}}}""", "client-a");
        assertTrue(checkout.contains("\"isError\":false"), checkout);

        String payments = handler.handle("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":
                {"name":"query_telemetry","arguments":{"signal":"LOGS","service":"orders-db"}}}""", "client-b");
        assertTrue(payments.contains("\"isError\":true"), payments);
        assertTrue(payments.contains("orders-db"), payments);
    }

    @Test
    void recoversFromMalformedJsonWithParseError() {
        String broken = handler.handle("{ this is not json");
        assertTrue(broken.contains("-32700"), broken);
        assertTrue(broken.contains("\"id\":null"), broken);
    }

    @Test
    void runsLifecycleTools() {
        String scenarios = handler.handle("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_scenarios"}}""");
        assertTrue(scenarios.contains("\"isError\":false"), scenarios);
        assertTrue(scenarios.contains("checkout-latency-db-pool"), scenarios);

        String episode = handler.handle("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":
                {"name":"start_episode","arguments":{"scenarioId":"payments-memory-leak","seed":7}}}""");
        assertTrue(episode.contains("\"isError\":false"), episode);
        assertTrue(episode.contains("episodeId"), episode);

        String drill = handler.handle("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":
                {"name":"run_incident_drill","arguments":{"scenarioId":"orders-bad-deployment"}}}""");
        assertTrue(drill.contains("\"isError\":false"), drill);
        assertTrue(drill.contains("\"solved\":true"), drill);
    }
}
