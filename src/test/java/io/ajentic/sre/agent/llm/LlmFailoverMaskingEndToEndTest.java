package io.ajentic.sre.agent.llm;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.FindRequestsResult;
import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.session.ChatTurnResult;
import io.ajentic.sre.agent.session.SessionService;
import io.ajentic.sre.test.FallbackLlmTestResource;
import io.ajentic.sre.test.WireMockLlmTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end failover through the CDI-wired composite client: a failing primary endpoint falls over to the
 * configured fallback, and the masking guardrail stays applied around the whole composite, so neither
 * endpoint receives raw identifiers.
 */
@QuarkusTest
@WithTestResource(WireMockLlmTestResource.class)
@WithTestResource(FallbackLlmTestResource.class)
class LlmFailoverMaskingEndToEndTest {

    @Inject
    SessionService sessions;

    @Test
    void failoverStaysMaskedAcrossEndpoints() {
        AgentSession session = sessions.create(
                AgentSessionConfig.forScenario("orders-bad-deployment").withModel("wm-primary-500"));
        ChatTurnResult turn = session.chat("If the pool is exhausted, contact admin@example.com immediately.");

        assertEquals("The fallback endpoint answered.", turn.answer());

        String primaryBody = bodyOf(WireMockLlmTestResource.server(), "/v1/chat/completions");
        String fallbackBody = bodyOf(FallbackLlmTestResource.server(), "/fb/chat/completions");

        assertTrue(primaryBody.contains("[[EMAIL_1]]"), primaryBody);
        assertTrue(fallbackBody.contains("[[EMAIL_1]]"), fallbackBody);
        assertFalse(primaryBody.contains("admin@example.com"), primaryBody);
        assertFalse(fallbackBody.contains("admin@example.com"), fallbackBody);
    }

    private static String bodyOf(com.github.tomakehurst.wiremock.WireMockServer server, String path) {
        FindRequestsResult requests = server.findRequestsMatching(
                postRequestedFor(urlPathEqualTo(path)).build());
        assertFalse(requests.getRequests().isEmpty(), path);
        return requests.getRequests().getFirst().getBodyAsString();
    }
}
