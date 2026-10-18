package io.ajentic.sre.agent;

import io.ajentic.sre.agent.llm.ChatMessage;
import io.ajentic.sre.agent.llm.LlmClient;
import io.ajentic.sre.agent.llm.LlmProperties;
import io.ajentic.sre.agent.llm.LlmRequest;
import io.ajentic.sre.agent.llm.LlmResponse;
import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.session.ChatTurnResult;
import io.ajentic.sre.agent.session.Goal;
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

import java.util.List;

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
    void unauthorizedMutationsAreReportedInsteadOfExecuted() {
        AgentSession session = new AgentSession(
                "read-only",
                AgentSessionConfig.forScenario("orders-bad-deployment").withModel(modelFor("wm-readonly")),
                llm,
                tools,
                environments.create("orders-bad-deployment", 3L),
                "You are a test SRE agent.",
                tool -> tool.capability() == Capability.READ);

        ChatTurnResult result = session.chat("Fix the incident.");

        assertFalse(result.observation().resolved());
        assertTrue(session.conversation().messages().stream()
                .anyMatch(message -> message.content() != null && message.content().contains("is not authorized")));
    }

    @Test
    void executesReadOnlyToolCallsAcrossVirtualThreads() {
        AgentSession session = session("orders-bad-deployment", modelFor("wm-parallel"));
        ChatTurnResult result = session.chat("Sweep telemetry in parallel.");

        assertEquals("Read-only sweep complete.", result.answer());
        assertEquals(9, session.conversation().messages().stream()
                .filter(message -> message.role() == io.ajentic.sre.agent.llm.ChatRole.TOOL)
                .count());
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
