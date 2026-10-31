package io.ajentic.sre.agent;

import io.ajentic.sre.agent.llm.LlmClient;
import io.ajentic.sre.agent.llm.LlmProperties;
import io.ajentic.sre.agent.masking.SensitiveDataMasker;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.tool.ToolAuthorization;
import io.ajentic.sre.agent.tool.ToolRegistry;
import io.ajentic.sre.rl.Environment;
import io.ajentic.sre.rl.episode.EpisodeResult;
import io.ajentic.sre.rl.episode.EpisodeRunner;
import io.ajentic.sre.rl.policy.LlmPolicy;
import io.ajentic.sre.rl.policy.PolicyKind;
import io.ajentic.sre.rl.policy.PolicyFactory;
import io.ajentic.sre.rl.policy.RandomPolicy;
import io.ajentic.sre.rl.simulation.EnvironmentFactory;
import io.ajentic.sre.rl.TerminationReason;
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
 * Drives an episode with the {@code LLM} policy: the ReAct session is the policy, the {@code EpisodeRunner}
 * owns stepping, and masking stays on the LLM path. Runs offline against WireMock; set
 * {@code -Dajentic.llm.live=true} to point it at a live endpoint instead.
 */
@QuarkusTest
@WithTestResource(WireMockLlmTestResource.class)
class LlmPolicyEndToEndTest {

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
    EpisodeRunner runner;

    @Inject
    PolicyFactory policies;

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
        LlmPolicy policy = llmPolicy("orders-bad-deployment", "wm-orders");
        Environment environment = environments.create("orders-bad-deployment", 1L);

        EpisodeResult result = runner.run("llm-policy", environment, policy);

        assertTrue(result.solved(), result::toString);
        assertEquals(TerminationReason.RESOLVED, result.terminationReason(), result::toString);
    }

    @Test
    void mismatchedScenarioYieldsCleanFailure() {
        // The fallback stub never calls tools, so the episode burns its step budget without an exception.
        LlmPolicy policy = llmPolicy("orders-bad-deployment", "wm-default");
        Environment environment = environments.create("orders-bad-deployment", 1L);

        EpisodeResult result = runner.run("llm-policy-mismatch", environment, policy);

        assertFalse(result.solved(), result::toString);
        assertEquals(TerminationReason.MAX_STEPS, result.terminationReason(), result::toString);
    }

    @Test
    void maskingStaysActiveForLlmPolicy() {
        // wm-mask-turns replays raw identifiers inside tool call arguments, so any leak hits the wire.
        LlmPolicy policy = llmPolicy("orders-bad-deployment", "wm-mask-turns");
        Environment environment = environments.create("orders-bad-deployment", 1L);

        EpisodeResult result = runner.run("llm-policy-masking", environment, policy);

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
        EpisodeResult a = runner.run("random-a", environments.create("orders-bad-deployment", 1L), RandomPolicy.withSeed(42L));
        EpisodeResult b = runner.run("random-b", environments.create("orders-bad-deployment", 1L), RandomPolicy.withSeed(42L));

        assertEquals(a.steps(), b.steps(), "same seed must replay the same trajectory");
        assertEquals(a.totalReward(), b.totalReward(), "same seed must replay the same reward");
        assertEquals(TerminationReason.MAX_STEPS, a.terminationReason(), a::toString);
    }

    @Test
    void policyFactoryResolvesTheLlmPolicy() {
        PolicyKind kind = PolicyKind.fromName("llm");
        assertEquals(PolicyKind.LLM, kind);
        // Resolved via the same qualifier lookup used by episodes; construction is lazy, no LLM call yet.
        policies.byName("llm");
    }

    private LlmPolicy llmPolicy(String scenarioId, String wireMockModel) {
        return new LlmPolicy(llm, tools, authorization, masker, meters,
                AgentSessionConfig.forScenario(scenarioId).withModel(modelFor(wireMockModel)));
    }

    private String modelFor(String wireMockModel) {
        return WireMockLlmTestResource.live() ? llmProperties.model() : wireMockModel;
    }
}
