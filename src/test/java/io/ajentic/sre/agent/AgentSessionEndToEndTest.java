package io.ajentic.sre.agent;

import io.ajentic.sre.agent.llm.ChatMessage;
import io.ajentic.sre.agent.llm.ChatRole;
import io.ajentic.sre.agent.llm.LlmClient;
import io.ajentic.sre.agent.llm.LlmProperties;
import io.ajentic.sre.agent.llm.LlmRequest;
import io.ajentic.sre.agent.llm.LlmResponse;
import io.ajentic.sre.agent.llm.ToolCall;
import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.session.ChatTurnResult;
import io.ajentic.sre.agent.session.Goal;
import io.ajentic.sre.agent.tool.AuthorizationDecision;
import io.ajentic.sre.agent.tool.Capability;
import io.ajentic.sre.agent.tool.ToolRegistry;
import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.simulation.EnvironmentFactory;
import io.ajentic.sre.test.WireMockLlmTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the ReAct loop through the real {@code MaskingLlmClient -> OpenAiCompatibleLlmClient -> HTTP -> WireMock}
 * path, so the OpenAI wire contract is exercised end to end. The same suite can be pointed at a live endpoint with
 * {@code -Dajentic.llm.live=true}: WireMock is skipped, the configured model is used instead of the stub model, and
 * the stub-specific request verifications are disabled.
 */
@QuarkusTest
@WithTestResource(WireMockLlmTestResource.class)
class AgentSessionEndToEndTest {

    @Inject
    LlmClient llm;

    @Inject
    ToolRegistry tools;

    @Inject
    EnvironmentFactory environments;

    @Inject
    LlmProperties llmProperties;

    @BeforeEach
    void resetWireMock() {
        if (!WireMockLlmTestResource.live()) {
            WireMockLlmTestResource.server().resetRequests();
        }
        WireMockLlmTestResource.resetScenarios();
    }

    @Test
    void agentResolvesAnIncidentThroughToolCalls() {
        AgentSession session = session("orders-bad-deployment", modelFor("wm-orders"));
        ChatTurnResult result = session.chat("Why are orders failing?");

        assertTrue(result.observation().diagnosed(), result::toString);
        assertTrue(result.observation().resolved(), result::toString);
        assertFalse(result.answer().isBlank());
    }

    @Test
    void stopsBeforeTheNextLlmCallWhenTheTokenBudgetIsSpent() {
        AgentSessionConfig config = AgentSessionConfig.forScenario("orders-bad-deployment")
                .withModel(modelFor("wm-orders"))
                .withMaxTokens(1);
        Environment environment = environments.create(config.scenarioId(), config.seed());
        AgentSession session = new AgentSession(
                "session-budget", config, llm, tools, environment, "You are a test SRE agent.");

        ChatTurnResult result = session.chat("Investigate the incident.");

        assertTrue(result.answer().startsWith("Token budget exhausted"), result.answer());
        assertEquals(49, result.usage().totalTokens());
        assertEquals(1, session.conversation().messages().stream()
                .filter(message -> message.role() == io.ajentic.sre.agent.llm.ChatRole.TOOL)
                .count(), "the second LLM call must not happen");
    }

    @Test
    void chatUntilGoalRunsUntilTheGoalIsSatisfied() {
        AgentSession session = session("orders-bad-deployment", modelFor("wm-goal"));

        Goal goal = new Goal("a root cause was reported", conversation -> conversation.messages().stream()
                .anyMatch(message -> "Root cause confirmed.".equals(message.content())));

        session.chatUntilGoal("Investigate the incident.", goal);

        assertTrue(goal.completion().test(session.conversation()));
    }

    @Test
    void masksIdentifiersBeforeTheModelAndUnmasksItsResponse() {
        LlmResponse response = llm.complete(new LlmRequest(
                List.of(ChatMessage.user("pod checkout-api-7d9f8c6b5-x2k4q on 10.1.2.3 owned by ops@example.com "
                        + "using sk-abcdefghijklmnopqrstuvwxyz")),
                List.of(),
                modelFor("wm-mask")));

        assertTrue(response.content().contains("checkout-api-7d9f8c6b5-x2k4q"), response.content());
        assertTrue(response.content().contains("10.1.2.3"), response.content());
        assertTrue(response.content().contains("ops@example.com"), response.content());
        assertTrue(response.content().contains("sk-abcdefghijklmnopqrstuvwxyz"), response.content());
        if (!WireMockLlmTestResource.live()) {
            WireMockLlmTestResource.server().verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                    .withRequestBody(matchingJsonPath("$.messages[0].content", containing("[[POD_1]]")))
                    .withRequestBody(matchingJsonPath("$.messages[0].content", containing("[[IP_1]]")))
                    .withRequestBody(matchingJsonPath("$.messages[0].content", containing("[[EMAIL_1]]")))
                    .withRequestBody(matchingJsonPath("$.messages[0].content", containing("[[API_KEY_1]]"))));
        }
    }

    @Test
    void masksToolCallArgumentsReplayedInLaterTurns() {
        AgentSession session = session("orders-bad-deployment", modelFor("wm-mask-turns"));

        assertEquals("Diagnosis recorded.", session.chat("Investigate the incident.").answer());

        if (WireMockLlmTestResource.live()) {
            return;
        }
        List<String> bodies = WireMockLlmTestResource.server().getAllServeEvents().stream()
                .map(event -> event.getRequest().getBodyAsString())
                .toList();
        assertFalse(bodies.isEmpty(), "expected at least one outbound request");
        for (String body : bodies) {
            assertFalse(body.contains("10.1.2.3"), body);
            assertFalse(body.contains("ops@example.com"), body);
            assertFalse(body.contains("arn:aws:logs"), body);
        }
        assertTrue(bodies.stream().anyMatch(body -> body.contains("[[IP_1]]")), bodies.toString());
        assertTrue(bodies.stream().anyMatch(body -> body.contains("[[ARN_1]]")), bodies.toString());
        assertTrue(bodies.stream().anyMatch(body -> body.contains("[[EMAIL_1]]")), bodies.toString());
        assertTrue(bodies.stream().noneMatch(body -> body.contains("[[IP_2]]")), bodies.toString());
    }

    @Test
    void masksNestedToolCallArgumentsBeforeTheModel() {
        ToolCall nested = new ToolCall("call-1", "lookup", Map.of(
                "filters", Map.of("hosts", List.of("10.9.8.7", "oncall@example.com")),
                "limit", 5));

        llm.complete(new LlmRequest(
                List.of(ChatMessage.assistant("checking", List.of(nested))),
                List.of(),
                modelFor("wm-mask")));

        if (WireMockLlmTestResource.live()) {
            return;
        }
        WireMockLlmTestResource.server().verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.messages[0].tool_calls[0].function.arguments",
                        containing("[[IP_1]]")))
                .withRequestBody(matchingJsonPath("$.messages[0].tool_calls[0].function.arguments",
                        containing("[[EMAIL_1]]"))));
        String body = WireMockLlmTestResource.server().getAllServeEvents().getFirst()
                .getRequest().getBodyAsString();
        assertFalse(body.contains("10.9.8.7"), body);
        assertFalse(body.contains("oncall@example.com"), body);
    }

    @Test
    void masksMulticloudIdentifiersBeforeTheModel() {
        String gcp = "//storage.googleapis.com/projects/billing-proj-123456789/objects/app.log";
        String azure = "/subscriptions/550e8400-e29b-41d4-a716-446655440000/resourceGroups/prod-rg/website";
        String k8s = "db-creds:550e8400-e29b-41d4-a716-446655440000";
        String bearer = "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzcmUifQ.c2ln";

        llm.complete(new LlmRequest(
                List.of(ChatMessage.user(gcp + " then " + azure + " secret " + k8s + " with " + bearer)),
                List.of(),
                modelFor("wm-mask")));

        if (WireMockLlmTestResource.live()) {
            return;
        }
        String body = WireMockLlmTestResource.server().getAllServeEvents().getFirst()
                .getRequest().getBodyAsString();
        assertFalse(body.contains("storage.googleapis.com"), body);
        assertFalse(body.contains("550e8400-e29b-41d4-a716-446655440000"), body);
        assertFalse(body.contains("eyJhbGciOiJIUzI1NiJ9"), body);
        assertTrue(body.contains("[[GCP_1]]") && body.contains("[[AZURE_1]]")
                && body.contains("[[K8S_1]]") && body.contains("[[BEARER_1]]"), body);
    }

    @Test
    void unauthorizedMutationsAreReportedInsteadOfExecuted() {
        AgentSession session = new AgentSession(
                "read-only",
                AgentSessionConfig.forScenario("orders-bad-deployment").withModel(modelFor("wm-readonly")),
                llm,
                tools,
                environments.create("orders-bad-deployment", 3L),
                "You are a test SRE agent.",
                tool -> tool.capability() == Capability.READ ? AuthorizationDecision.ALLOW : AuthorizationDecision.DENY);

        ChatTurnResult result = session.chat("Fix the incident.");

        assertFalse(result.observation().resolved());
        assertTrue(session.conversation().messages().stream()
                .anyMatch(message -> message.content() != null && message.content().contains("is not authorized")));
    }

    @Test
    void remediationBeforeDiagnosisDoesNotEndTheEpisodeSolved() {
        AgentSession session = session("orders-bad-deployment", modelFor("wm-early-remediation"));

        ChatTurnResult result = session.chat("Remediate first, diagnose after.");

        assertTrue(result.observation().diagnosed(), result::toString);
        assertTrue(result.observation().resolved(), result::toString);
        assertTrue(session.environment().isTerminal(), "diagnosis plus resolved faults should terminate");
        assertEquals(2, session.environment().observe().step());
    }

    @Test
    void executesReadOnlyToolCallsAcrossVirtualThreads() {
        AgentSession session = session("orders-bad-deployment", modelFor("wm-parallel"));
        ChatTurnResult result = session.chat("Sweep telemetry in parallel.");

        assertEquals("Read-only sweep complete.", result.answer());
        assertEquals(9, session.conversation().messages().stream()
                .filter(message -> message.role() == io.ajentic.sre.agent.llm.ChatRole.TOOL)
                .count());
        assertEquals(9, session.environment().observe().step(), "read tools must advance the simulation clock");
    }

    @Test
    void stopsAfterRepeatedIdenticalToolCallsBeforeMaxIterations() {
        ToolCall same = new ToolCall("call-1", "query_telemetry",
                Map.of("signal", "LOGS", "service", "orders-api"));
        int[] calls = new int[1];
        AgentSession session = new AgentSession(
                "session-stall",
                AgentSessionConfig.forScenario("orders-bad-deployment").withModel(modelFor("wm-orders")),
                request -> { calls[0]++; return LlmResponse.toolCalls(List.of(same)); },
                tools,
                environments.create("orders-bad-deployment", 1L),
                "You are a test SRE agent.");

        ChatTurnResult result = session.chat("Investigate the incident.");

        assertTrue(result.answer().startsWith("Stalled"), result.answer());
        assertEquals(3, calls[0], "escalate-after stops the loop, not maxIterations");
        assertEquals(3, session.conversation().messages().stream()
                .filter(message -> message.role() == ChatRole.TOOL).count());
    }

    @Test
    void repeatedReadsAcrossTurnsAreNotFlagged() {
        ToolCall read = new ToolCall("call-1", "query_telemetry",
                Map.of("signal", "LOGS", "service", "orders-api"));
        Deque<LlmResponse> script = new ArrayDeque<>(List.of(
                LlmResponse.toolCalls(List.of(read)),
                LlmResponse.finalAnswer("first investigation complete"),
                LlmResponse.toolCalls(List.of(read)),
                LlmResponse.finalAnswer("second investigation complete")));
        int[] calls = new int[1];
        AgentSession session = new AgentSession(
                "session-cross",
                AgentSessionConfig.forScenario("orders-bad-deployment").withModel(modelFor("wm-orders")),
                request -> { calls[0]++; return script.poll(); },
                tools,
                environments.create("orders-bad-deployment", 1L),
                "You are a test SRE agent.");

        ChatTurnResult first = session.chat("Investigate turn one.");
        ChatTurnResult second = session.chat("Investigate turn two.");

        assertEquals("first investigation complete", first.answer(), first::toString);
        assertEquals("second investigation complete", second.answer(), second::toString);
        assertEquals(4, calls[0], "both turns ran their read without a premature stall");
        assertEquals(2, session.environment().observe().step(), "each turn executed the read once");
    }

    private AgentSession session(String scenarioId, String model) {
        Environment environment = environments.create(scenarioId, 1L);
        return new AgentSession(
                "session-" + scenarioId,
                AgentSessionConfig.forScenario(scenarioId).withModel(model),
                llm,
                tools,
                environment,
                "You are a test SRE agent.");
    }

    private String modelFor(String wireMockModel) {
        return WireMockLlmTestResource.live() ? llmProperties.model() : wireMockModel;
    }
}
