package io.ajentic.sre.simulation.environment;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.ajentic.sre.simulation.StepResult;
import io.ajentic.sre.simulation.TelemetrySignal;
import io.ajentic.sre.simulation.action.ApplyRemediation;
import io.ajentic.sre.simulation.action.QueryTelemetry;
import io.ajentic.sre.simulation.action.RemediationKind;
import io.ajentic.sre.simulation.environment.cloud.CloudLiveEnvironment;
import io.ajentic.sre.simulation.environment.local.LocalContainerEnvironment;
import io.ajentic.sre.simulation.scorecard.IncidentScorecard;
import io.ajentic.sre.simulation.Scenario;
import io.ajentic.sre.simulation.ScenarioLibrary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Drives the live telemetry backends against WireMock stubs standing in for Prometheus and Loki:
 * metrics and logs come over HTTP, 5xx upstream surfaces as a tool error, the circuit breaker fails
 * fast, local-container dispatches remediations, and cloud-live never touches the live system.
 */
class LiveTelemetryEnvironmentTest {

    private static final String PROMETHEUS_ERROR_BODY = """
            {"status": "success", "data": {"resultType": "matrix", "result": [{"metric": {"service": "orders-api"}, "values": [[1735689600, "0.12"], [1735689660, "0.12"]]}]}}
            """;
    private static final String PROMETHEUS_LATENCY_BODY = """
            {"status": "success", "data": {"resultType": "matrix", "result": [{"metric": {"service": "orders-api"}, "values": [[1735689600, "420.5"], [1735689660, "420.5"]]}]}}
            """;
    private static final String LOKI_BODY = """
            {"status": "success", "data": {"resultType": "streams", "result": [{"stream": {"service": "orders-api"}, "values": [["1735689600000000000", "2026-01-01T00:00:00Z checkout failed: connection refused to orders-db"]]}]}}
            """;

    private static WireMockServer server;

    private final Scenario scenario = new ScenarioLibrary().byId("orders-bad-deployment").orElseThrow();

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void resetStubs() {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/api/v1/query_range"))
                .withQueryParam("query", containing("status="))
                .willReturn(WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(PROMETHEUS_ERROR_BODY)));
        server.stubFor(get(urlPathEqualTo("/api/v1/query_range"))
                .withQueryParam("query", containing("histogram_quantile"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(PROMETHEUS_LATENCY_BODY)));
        server.stubFor(get(urlPathEqualTo("/loki/api/v1/query_range"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(LOKI_BODY)));
        server.stubFor(post(urlEqualTo("/localstack/remediation"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200).withBody("{}")));
    }

    @Test
    void metricsAndLogsComeFromLiveEndpoints() {
        LocalContainerEnvironment environment = localEnvironment();

        List<io.ajentic.sre.simulation.ObservedSignal> metrics = environment.signals(TelemetrySignal.METRICS, "orders-api");
        var attributes = metrics.getFirst().attributes();
        assertEquals(0.12, ((Number) attributes.get("errorRate")).doubleValue(), 0.001);
        assertEquals(420.5, ((Number) attributes.get("p95LatencyMs")).doubleValue(), 0.001);

        List<io.ajentic.sre.simulation.ObservedSignal> logs = environment.signals(TelemetrySignal.LOGS, "orders-api");
        assertTrue(logs.stream().anyMatch(signal -> signal.summary().contains("checkout failed")), logs.toString());
    }

    @Test
    void localContainerDispatchesRemediation() {
        LocalContainerEnvironment environment = localEnvironment();

        StepResult result = environment.step(new ApplyRemediation(RemediationKind.RESTART, "orders-api"));

        assertTrue(result.observation().feedback().contains("dispatched"), result.observation().feedback());
        server.verify(postRequestedFor(urlEqualTo("/localstack/remediation"))
                .withRequestBody(containing("\"service\":\"orders-api\"")));
    }

    @Test
    void cloudLiveRunsInShadowModeWithoutTouchingTheLiveSystem() {
        CloudLiveEnvironment environment = new CloudLiveEnvironment(scenario, liveConfig(), new IncidentScorecard());

        StepResult result = environment.step(new ApplyRemediation(RemediationKind.RESTART, "orders-api"));

        assertTrue(result.observation().feedback().contains("Shadow mode"), result.observation().feedback());
        assertEquals(0, server.countRequestsMatching(postRequestedFor(urlEqualTo("/localstack/remediation")).build()).getCount());
    }

    @Test
    void upstream5xxSurfacesAsToolErrorNotACrash() {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(WireMock.aResponse().withStatus(500)));
        server.stubFor(get(urlPathEqualTo("/loki/api/v1/query_range"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(LOKI_BODY)));

        LocalContainerEnvironment environment = localEnvironment();

        StepResult result = environment.step(new QueryTelemetry(TelemetrySignal.METRICS, "orders-api"));

        assertTrue(result.observation().feedback().startsWith("Telemetry backend error"), result.observation().feedback());
        assertFalse(result.observation().terminal());
    }

    @Test
    void circuitBreakerFailsFastWithoutFurtherRequests() {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(WireMock.aResponse().withStatus(500)));

        LiveHttp http = new LiveHttp(Duration.ofSeconds(1), 1);
        for (int i = 0; i < 3; i++) {
            assertThrows(LiveHttpException.class, () -> http.get(base() + "/api/v1/query_range"));
        }
        var open = assertThrows(LiveHttpException.class, () -> http.get(base() + "/api/v1/query_range"));
        assertTrue(open.getMessage().contains("circuit breaker open"), open.getMessage());
        assertEquals(3, server.countRequestsMatching(getRequestedFor(urlPathEqualTo("/api/v1/query_range")).build()).getCount());
    }

    private LocalContainerEnvironment localEnvironment() {
        return new LocalContainerEnvironment(scenario, liveConfig(), new IncidentScorecard());
    }

    private LiveTelemetryConfig liveConfig() {
        return new LiveTelemetryConfig(
                base(),
                base() + "/loki",
                base() + "/localstack/remediation",
                Duration.ofSeconds(5));
    }

    private String base() {
        return "http://localhost:" + server.port();
    }
}
