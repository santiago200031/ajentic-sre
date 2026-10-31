package io.ajentic.sre.api.webhook;

import io.ajentic.sre.test.WireMockLlmTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@WithTestResource(WireMockLlmTestResource.class)
@io.quarkus.test.junit.TestProfile(WebhookEndToEndTest.WebhookTestProfile.class)
class WebhookEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SECRET = "webhook-secret";

    private static final String ALERTMANAGER_BODY = """
            {"alerts":[{"labels":{"alertname":"HighLatency","service":"orders-api","severity":"critical"},
            "annotations":{"summary":"p95 latency above 800ms on orders-api"}}]}
            """;

    private static final String ALERTMANAGER_BODY_2 = """
            {"alerts":[{"labels":{"alertname":"PoolExhausted","service":"orders-api","severity":"warning"},
            "annotations":{"summary":"db pool exhausted on orders-api"}}]}
            """;

    private static final String ALERTMANAGER_BODY_3 = """
            {"alerts":[{"labels":{"alertname":"DiskFull","service":"orders-api","severity":"critical"},
            "annotations":{"summary":"disk pressure on orders-api"}}]}
            """;

    private static final String CLOUDWATCH_BODY =
            "{\"Message\":\"{\\\"AlarmName\\\":\\\"orders-api-latency\\\",\\\"NewState\\\":\\\"ALARM\\\",\\\"StateReason\\\":\\\"p95 latency 900ms above threshold 800ms\\\"}\",\"MessageId\":\"m-1\"}";

    public static class WebhookTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("ajentic.webhooks.secret", SECRET);
        }
    }

    @Test
    void alertmanagerIntakeCreatesSessionAndPostsReport() {
        JsonNode view = post("/api/v1/webhooks/alertmanager", ALERTMANAGER_BODY, "X-Alertmanager-Secret", SECRET);

        assertEquals("orders-bad-deployment", view.get("scenarioId").asText());
        assertEquals("orders-api", view.get("service").asText());
        assertEquals("ALERTMANAGER", view.get("source").asText());
        assertEquals("critical", view.get("severity").asText());
        assertFalse(view.get("duplicate").asBoolean());
        assertTrue(view.get("sessionId").asText().length() > 0);
        assertTrue(view.get("report").asText().length() > 0);
        assertEquals(64, view.get("fingerprint").asText().length());
    }

    @Test
    void duplicateFingerprintIsCoalescedOntoTheOriginalReport() {
        JsonNode first = post("/api/v1/webhooks/alertmanager", ALERTMANAGER_BODY_2, "X-Alertmanager-Secret", SECRET);
        JsonNode second = post("/api/v1/webhooks/alertmanager", ALERTMANAGER_BODY_2, "X-Alertmanager-Secret", SECRET);

        assertTrue(second.get("duplicate").asBoolean());
        assertEquals(first.get("sessionId").asText(), second.get("sessionId").asText());
        assertEquals(first.get("fingerprint").asText(), second.get("fingerprint").asText());
        assertEquals(first.get("report").asText(), second.get("report").asText());
    }

    @Test
    void badSecretIsRejected() {
        int status = given()
                .header("X-Alertmanager-Secret", "wrong")
                .contentType("application/json")
                .body(ALERTMANAGER_BODY)
                .when().post("/api/v1/webhooks/alertmanager")
                .then()
                .extract().response().getStatusCode();
        assertEquals(401, status);
    }

    @Test
    void pagerdutyNormalizesTheIncident() {
        JsonNode view = post("/api/v1/webhooks/pagerduty",
                """
                {"incident_number":123,"incident_key":"pd-1","title":"orders-api degraded",
                "severity":"critical","service_name":"orders-api"}
                """,
                "Authorization", "Token ptoken=" + SECRET);
        assertEquals("PAGERDUTY", view.get("source").asText());
        assertEquals("orders-api", view.get("service").asText());
        assertEquals("orders-api degraded", view.get("summary").asText());
        assertFalse(view.get("duplicate").asBoolean());
    }

    @Test
    void cloudWatchSnsNormalizesTheAlarm() {
        JsonNode view = post("/api/v1/webhooks/cloudwatch-sns", CLOUDWATCH_BODY, "X-SNS-Secret", SECRET);
        assertEquals("CLOUDWATCH_SNS", view.get("source").asText());
        assertEquals("orders-api-latency", view.get("service").asText());
        assertEquals("ALARM", view.get("severity").asText());
        assertTrue(view.get("summary").asText().contains("900ms"));
    }

    @Test
    void webhookNotificationChannelPostsTheReport() {
        com.github.tomakehurst.wiremock.WireMockServer server =
                new com.github.tomakehurst.wiremock.WireMockServer(
                        com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
        server.start();
        try {
            server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                    com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/notify"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200)));
            Alert alert = new Alert("ALERTMANAGER", "orders-api", "summary", "critical", "fp-1");
            AlertReport report = new AlertReport(alert, "orders-bad-deployment", "sess-1", "root cause found",
                    Instant.now(), Instant.now(), false);
            new WebhookNotificationChannel(server.url("/notify")).notify(report);
            com.github.tomakehurst.wiremock.verification.FindRequestsResult requests =
                    server.findRequestsMatching(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                            com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/notify"))
                            .withHeader("content-type", com.github.tomakehurst.wiremock.client.WireMock.equalTo("application/json"))
                            .build());
            assertEquals(1, requests.getRequests().size());
            String body = requests.getRequests().getFirst().getBodyAsString();
            assertTrue(body.contains("root cause found"), body);
        } finally {
            server.stop();
        }
    }

    @Test
    void reportsAreListedForPostMortems() {
        JsonNode listed = post("/api/v1/webhooks/alertmanager", ALERTMANAGER_BODY_3, "X-Alertmanager-Secret", SECRET);
        String fingerprint = listed.get("fingerprint").asText();

        String body = given()
                .contentType("application/json")
                .when().get("/api/v1/webhooks")
                .then()
                .statusCode(200)
                .extract().body().asString();
        JsonNode reports = MAPPER.readTree(body);
        assertTrue(reports.isArray());
        boolean seen = false;
        for (JsonNode report : reports) {
            if (report.get("fingerprint").asText().equals(fingerprint)) {
                seen = true;
                break;
            }
        }
        assertTrue(seen);
    }

    private static JsonNode post(String url, String body, String headerName, String headerValue) {
        String raw = given()
                .header(headerName, headerValue)
                .contentType("application/json")
                .body(body)
                .when().post(url)
                .then()
                .statusCode(200)
                .extract().body().asString();
        return MAPPER.readTree(raw);
    }
}
