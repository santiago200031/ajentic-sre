package io.ajentic.sre.agent;

import io.ajentic.sre.agent.llm.LlmClient;
import io.ajentic.sre.agent.llm.LlmProperties;
import io.ajentic.sre.agent.masking.SensitiveDataMasker;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.tool.ToolAuthorization;
import io.ajentic.sre.agent.tool.ToolRegistry;
import io.ajentic.sre.simulation.Environment;
import io.ajentic.sre.simulation.drill.SimulationResult;
import io.ajentic.sre.simulation.drill.SimulationRunner;
import io.ajentic.sre.simulation.playbook.LlmPlaybook;
import io.ajentic.sre.simulation.playbook.PlaybookKind;
import io.ajentic.sre.simulation.playbook.PlaybookFactory;
import io.ajentic.sre.simulation.playbook.RandomPlaybook;
import io.ajentic.sre.simulation.EnvironmentFactory;
import io.ajentic.sre.simulation.TerminationReason;
import io.ajentic.sre.test.WireMockLlmTestResource;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives an episode with the {@code LLM} policy: the ReAct session is the policy, the {@code SimulationRunner}
 * owns stepping, and masking stays on the LLM path. Runs offline against WireMock; set
 * {@code -Dajentic.llm.live=true} to point it at a live endpoint instead.
 */
@QuarkusTest
@WithTestResource(WireMockLlmTestResource.class)
class LlmPlaybookEndToEndTest {

    @Inject
    LlmClient llm;

    @Inject
    ToolRegistry tools;

    @Inject
    ToolAuthorization authorization;

    @Inject
    SensitiveDataMasker masker;

    @Inject
    MeterRegistry meters;

    @Inject
    EnvironmentFactory environments;

    @Inject
    SimulationRunner runner;

    @Inject
    PlaybookFactory policies;

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
    void scriptedPolicySolvesTheScenario() {
        LlmPlaybook policy = llmPolicy("orders-bad-deployment", "wm-orders");
        Environment environment = environments.create("orders-bad-deployment", 1L);

        SimulationResult result = runner.run("llm-policy", environment, policy);

        assertTrue(result.solved(), result::toString);
        assertEquals(TerminationReason.RESOLVED, result.terminationReason(), result::toString);
    }

    @Test
    void mismatchedScenarioYieldsCleanFailure() {
        // The fallback stub never calls tools, so the episode burns its step budget without an exception.
        LlmPlaybook policy = llmPolicy("orders-bad-deployment", "wm-default");
        Environment environment = environments.create("orders-bad-deployment", 1L);

        SimulationResult result = runner.run("llm-policy-mismatch", environment, policy);

        assertFalse(result.solved(), result::toString);
        assertEquals(TerminationReason.MAX_STEPS, result.terminationReason(), result::toString);
    }

    @Test
    void maskingStaysActiveForLlmPlaybook() {
        // wm-mask-turns replays raw identifiers inside tool call arguments, so any leak hits the wire.
        LlmPlaybook policy = llmPolicy("orders-bad-deployment", "wm-mask-turns");
        Environment environment = environments.create("orders-bad-deployment", 1L);

        SimulationResult result = runner.run("llm-policy-masking", environment, policy);

        assertEquals(TerminationReason.MAX_STEPS, result.terminationReason(), result::toString);
        if (WireMockLlmTestResource.live()) {
            return;
        }
        List<String> bodies = WireMockLlmTestResource.server().getAllServeEvents().stream()
                .map(event -> event.getRequest().getBodyAsString())
                .toList();
        assertTrue(bodies.stream().anyMatch(body -> body.contains("[[IP_1]]")), bodies.toString());
        assertTrue(bodies.stream().anyMatch(body -> body.contains("[[ARN_1]]")), bodies.toString());
        assertTrue(bodies.stream().anyMatch(body -> body.contains("[[EMAIL_1]]")), bodies.toString());
        for (String body : bodies) {
            assertFalse(body.contains("10.1.2.3"), body);
            assertFalse(body.contains("arn:aws:logs"), body);
            assertFalse(body.contains("ops@example.com"), body);
        }
    }

    @Test
    void randomPolicyIsDeterministicPerSeed() {
        SimulationResult a = runner.run("random-a", environments.create("orders-bad-deployment", 1L), RandomPlaybook.withSeed(42L));
        SimulationResult b = runner.run("random-b", environments.create("orders-bad-deployment", 1L), RandomPlaybook.withSeed(42L));

        assertEquals(a.steps(), b.steps(), "same seed must replay the same trajectory");
        assertEquals(a.totalReward(), b.totalReward(), "same seed must replay the same reward");
        assertEquals(TerminationReason.MAX_STEPS, a.terminationReason(), a::toString);
    }

    @Test
    void policyFactoryResolvesTheLlmPlaybook() {
        PlaybookKind kind = PlaybookKind.fromName("llm");
        assertEquals(PlaybookKind.LLM, kind);
        // Resolved via the same qualifier lookup used by episodes; construction is lazy, no LLM call yet.
        policies.byName("llm");
    }

    private LlmPlaybook llmPolicy(String scenarioId, String wireMockModel) {
        return new LlmPlaybook(llm, tools, authorization, masker, meters,
                AgentSessionConfig.forScenario(scenarioId).withModel(modelFor(wireMockModel)));
    }

    private String modelFor(String wireMockModel) {
        return WireMockLlmTestResource.live() ? llmProperties.model() : wireMockModel;
    }
}
