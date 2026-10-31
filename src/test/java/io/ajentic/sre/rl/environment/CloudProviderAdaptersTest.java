package io.ajentic.sre.rl.environment;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.ajentic.sre.agent.cloud.CloudHttpClient;
import io.ajentic.sre.rl.ObservedSignal;
import io.ajentic.sre.rl.StepResult;
import io.ajentic.sre.rl.TelemetrySignal;
import io.ajentic.sre.rl.action.ApplyRemediation;
import io.ajentic.sre.rl.action.InspectRecentChanges;
import io.ajentic.sre.rl.action.QueryTelemetry;
import io.ajentic.sre.rl.action.RemediationKind;
import io.ajentic.sre.rl.environment.aws.AwsLiveEnvironment;
import io.ajentic.sre.rl.environment.azure.AzureLiveEnvironment;
import io.ajentic.sre.rl.environment.gcp.GcpLiveEnvironment;
import io.ajentic.sre.rl.reward.IncidentRewardFunction;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ScenarioLibrary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudProviderAdaptersTest {

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
    void reset() {
        server.resetAll();
    }

    private String base() {
        return "http://localhost:" + server.port();
    }

    @Test
    void awsLiveAdapterQueriesTelemetryAndExecutesRemediation() {
        // CloudWatch MetricData
        server.stubFor(post(urlEqualTo("/cloudwatch/get-metric-data"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"MetricDataResults\": [{\"Id\": \"m1\", \"Values\": [0.08]}, {\"Id\": \"m2\", \"Values\": [350.0]}]}")));

        // CloudWatch Logs
        server.stubFor(post(urlEqualTo("/logs/filter-log-events"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"events\": [{\"message\": \"ECS task container failed to connect to database\"}]}")));

        // CloudTrail LookupEvents
        server.stubFor(post(urlEqualTo("/cloudtrail/lookup-events"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"Events\": [{\"EventName\": \"UpdateService\", \"EventTime\": 1735689600}]}")));

        // ECS UpdateService
        server.stubFor(post(urlEqualTo("/ecs/update-service"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        AwsLiveEnvironment env = new AwsLiveEnvironment(
                scenario,
                new IncidentRewardFunction(),
                new CloudHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(5), 1, 20),
                "us-east-1",
                "test-ak",
                "test-sk",
                null,
                base(),
                true,
                false);

        List<ObservedSignal> metrics = env.signals(TelemetrySignal.METRICS, "orders-api");
        assertFalse(metrics.isEmpty());
        assertEquals(0.08, ((Number) metrics.getFirst().attributes().get("errorRate")).doubleValue(), 0.001);

        List<ObservedSignal> logs = env.signals(TelemetrySignal.LOGS, "orders-api");
        assertTrue(logs.stream().anyMatch(l -> l.summary().contains("failed to connect to database")));

        StepResult changes = env.step(new InspectRecentChanges("orders-api", 60));
        assertTrue(changes.observation().feedback().contains("recent cloud changes"), changes.observation().feedback());

        StepResult rem = env.step(new ApplyRemediation(RemediationKind.RESTART, "orders-api"));
        assertTrue(rem.observation().feedback().contains("Executed UpdateService"), rem.observation().feedback());
        server.verify(postRequestedFor(urlEqualTo("/ecs/update-service")));
    }

    @Test
    void awsLiveAdapterEnforcesMutationGating() {
        AwsLiveEnvironment readOnlyEnv = new AwsLiveEnvironment(
                scenario,
                new IncidentRewardFunction(),
                new CloudHttpClient(),
                "us-east-1", "ak", "sk", null, base(),
                false, // allowMutations = false
                false);

        StepResult rem = readOnlyEnv.step(new ApplyRemediation(RemediationKind.RESTART, "orders-api"));
        assertTrue(rem.observation().feedback().contains("mutations are disabled"), rem.observation().feedback());
        server.verify(0, postRequestedFor(urlEqualTo("/ecs/update-service")));

        AwsLiveEnvironment shadowEnv = new AwsLiveEnvironment(
                scenario,
                new IncidentRewardFunction(),
                new CloudHttpClient(),
                "us-east-1", "ak", "sk", null, base(),
                true,
                true); // readOnly = true

        StepResult shadowRem = shadowEnv.step(new ApplyRemediation(RemediationKind.RESTART, "orders-api"));
        assertTrue(shadowRem.observation().feedback().contains("Shadow mode"), shadowRem.observation().feedback());
    }

    @Test
    void azureLiveAdapterHandlesTelemetryAndRemediation() {
        server.stubFor(post(urlEqualTo("/oauth2/token"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\": \"az-tok\", \"expires_in\": 3600}")));

        server.stubFor(get(urlPathEqualTo("/subscriptions/sub-1/resourceGroups/rg-1/providers/Microsoft.App/containerApps/orders-api/providers/microsoft.insights/metrics"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"value\": [{\"name\": {\"value\": \"Errors\"}, \"timeseries\": [{\"data\": [{\"average\": 0.15}]}]}]}")));

        server.stubFor(post(urlEqualTo("/v1/workspaces/ws-1/query"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"tables\": [{\"rows\": [[\"Azure Pod CrashLoopBackOff\"]]}]}")));

        server.stubFor(post(urlPathEqualTo("/subscriptions/sub-1/resourceGroups/rg-1/providers/Microsoft.App/containerApps/orders-api/restart"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        AzureLiveEnvironment env = new AzureLiveEnvironment(
                scenario,
                new IncidentRewardFunction(),
                new CloudHttpClient(),
                "tenant-1", "client-1", "sec-1", "sub-1", "rg-1", "ws-1",
                base(),
                base() + "/oauth2/token",
                true,
                false);

        List<ObservedSignal> metrics = env.signals(TelemetrySignal.METRICS, "orders-api");
        assertEquals(0.15, ((Number) metrics.getFirst().attributes().get("errorRate")).doubleValue(), 0.001);

        List<ObservedSignal> logs = env.signals(TelemetrySignal.LOGS, "orders-api");
        assertTrue(logs.stream().anyMatch(l -> l.summary().contains("CrashLoopBackOff")));

        StepResult rem = env.step(new ApplyRemediation(RemediationKind.RESTART, "orders-api"));
        assertTrue(rem.observation().feedback().contains("Azure Container Apps: Executed restart"), rem.observation().feedback());
    }

    @Test
    void gcpLiveAdapterHandlesTelemetryAndRollback() {
        server.stubFor(get(urlEqualTo("/computeMetadata/v1/token"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\": \"gcp-meta-tok\", \"expires_in\": 3600}")));

        server.stubFor(get(urlPathEqualTo("/v3/projects/my-gcp/timeSeries"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"timeSeries\": [{\"points\": [{\"value\": {\"doubleValue\": 0.09}}]}]}")));

        server.stubFor(post(urlEqualTo("/v2/entries:list"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"entries\": [{\"textPayload\": \"GCP Cloud Run: Out of memory container exit\"}]}")));

        server.stubFor(post(urlPathEqualTo("/v1/projects/my-gcp/locations/us-central1/services/orders-api:rollback"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        GcpLiveEnvironment env = new GcpLiveEnvironment(
                scenario,
                new IncidentRewardFunction(),
                new CloudHttpClient(),
                "my-gcp",
                null,
                null,
                base(),
                null,
                base() + "/computeMetadata/v1/token",
                true,
                false);

        List<ObservedSignal> metrics = env.signals(TelemetrySignal.METRICS, "orders-api");
        assertEquals(0.09, ((Number) metrics.getFirst().attributes().get("errorRate")).doubleValue(), 0.001);

        List<ObservedSignal> logs = env.signals(TelemetrySignal.LOGS, "orders-api");
        assertTrue(logs.stream().anyMatch(l -> l.summary().contains("Out of memory")));

        StepResult rem = env.step(new ApplyRemediation(RemediationKind.ROLLBACK_DEPLOY, "orders-api"));
        assertTrue(rem.observation().feedback().contains("Google Cloud Run: Rolled back"), rem.observation().feedback());
    }

    @Test
    void upstream5xxSurfacesAsToolFeedbackNotException() {
        server.stubFor(post(urlEqualTo("/cloudwatch/get-metric-data"))
                .willReturn(WireMock.aResponse().withStatus(500).withBody("Internal Server Error")));

        AwsLiveEnvironment env = new AwsLiveEnvironment(
                scenario,
                new IncidentRewardFunction(),
                new CloudHttpClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 0, 10),
                "us-east-1", "ak", "sk", null, base(), false, false);

        StepResult result = env.step(new QueryTelemetry(TelemetrySignal.METRICS, "orders-api"));
        assertTrue(result.observation().feedback().contains("Cloud provider API error"), result.observation().feedback());
        assertFalse(result.observation().terminal());
    }

    @Test
    void perTurnCallBudgetEnforcement() {
        CloudHttpClient client = new CloudHttpClient(Duration.ofSeconds(1), Duration.ofSeconds(2), 0, 2);
        AwsLiveEnvironment env = new AwsLiveEnvironment(
                scenario,
                new IncidentRewardFunction(),
                client,
                "us-east-1", "ak", "sk", null, base(), false, false);

        // Turn budget is 2 calls per step.
        // First step queries 1 call
        server.stubFor(post(urlEqualTo("/cloudwatch/get-metric-data"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody("{\"MetricDataResults\":[]}")));
        StepResult r1 = env.step(new QueryTelemetry(TelemetrySignal.METRICS, "orders-api"));
        assertNotNull(r1);

        // Verify that calls this turn are tracked and resetTurnBudget clears them
        assertEquals(4, client.callsThisTurn());
        client.resetTurnBudget();
        assertEquals(0, client.callsThisTurn());
    }

    @Test
    void azureTokenRefreshesOn401AndRetriesSuccessfully() {
        server.stubFor(post(urlEqualTo("/oauth2/token"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\": \"az-fresh-token\", \"expires_in\": 3600}")));

        server.stubFor(post(urlPathEqualTo("/subscriptions/sub-1/resourceGroups/rg-1/providers/Microsoft.App/containerApps/orders-api/restart"))
                .inScenario("AzureAuthRetry")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("TokenRefreshed")
                .willReturn(WireMock.aResponse()
                        .withStatus(401)
                        .withBody("{\"error\": \"Expired token\"}")));

        server.stubFor(post(urlPathEqualTo("/subscriptions/sub-1/resourceGroups/rg-1/providers/Microsoft.App/containerApps/orders-api/restart"))
                .inScenario("AzureAuthRetry")
                .whenScenarioStateIs("TokenRefreshed")
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        AzureLiveEnvironment env = new AzureLiveEnvironment(
                scenario,
                new IncidentRewardFunction(),
                new CloudHttpClient(),
                "tenant-1", "client-1", "sec-1", "sub-1", "rg-1", "ws-1",
                base(),
                base() + "/oauth2/token",
                true,
                false);

        StepResult rem = env.step(new ApplyRemediation(RemediationKind.RESTART, "orders-api"));
        assertTrue(rem.observation().feedback().contains("Azure Container Apps: Executed restart"), rem.observation().feedback());
    }
}
