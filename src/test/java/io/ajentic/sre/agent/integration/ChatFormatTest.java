package io.ajentic.sre.agent.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IncidentSummary sample = new IncidentSummary(
            "inc-orders-404",
            "Connection pool saturated on orders-db",
            "RESOLVED",
            List.of("pool_size=10", "pr=https://github.com/my-org/orders-api/pull/42"),
            "Hikari pool starvation under load",
            "https://github.com/my-org/orders-api/pull/42",
            "SUCCESS",
            "orders-bad-deployment-42"
    );

    @Test
    void capturingChannelAssertsMarkdownGoldenFixture() throws Exception {
        CapturingNotificationChannel channel = new CapturingNotificationChannel("discord", ChatFormat.MARKDOWN);
        channel.post(sample);

        assertEquals(1, channel.capturedFormatted().size());
        String rendered = channel.capturedFormatted().getFirst().strip();
        String expected = loadFixture("markdown.txt").strip();
        assertEquals(expected, rendered);
    }

    @Test
    void capturingChannelAssertsSlackMrkdwnGoldenFixture() throws Exception {
        CapturingNotificationChannel channel = new CapturingNotificationChannel("slack", ChatFormat.SLACK_MRKDWN);
        channel.post(sample);

        assertEquals(1, channel.capturedFormatted().size());
        String rendered = channel.capturedFormatted().getFirst().strip();
        String expected = loadFixture("slack_mrkdwn.txt").strip();
        assertEquals(expected, rendered);
    }

    @Test
    void capturingChannelAssertsGoogleChatGoldenFixture() throws Exception {
        CapturingNotificationChannel channel = new CapturingNotificationChannel("google-chat", ChatFormat.GOOGLE_CHAT);
        channel.post(sample);

        assertEquals(1, channel.capturedFormatted().size());
        String rendered = channel.capturedFormatted().getFirst().strip();
        String expected = loadFixture("google_chat.txt").strip();
        assertEquals(expected, rendered);
    }

    @Test
    void capturingChannelAssertsTelegramGoldenFixture() throws Exception {
        CapturingNotificationChannel channel = new CapturingNotificationChannel("telegram", ChatFormat.TELEGRAM);
        channel.post(sample);

        assertEquals(1, channel.capturedFormatted().size());
        String rendered = channel.capturedFormatted().getFirst().strip();
        String expected = loadFixture("telegram.txt").strip();
        assertEquals(expected, rendered);
    }

    @Test
    void capturingChannelAssertsTeamsAdaptiveCardStructure() throws Exception {
        CapturingNotificationChannel channel = new CapturingNotificationChannel("teams", ChatFormat.TEAMS_CARD);
        channel.post(sample);

        assertEquals(1, channel.capturedFormatted().size());
        String rendered = channel.capturedFormatted().getFirst();
        JsonNode root = MAPPER.readTree(rendered);

        assertEquals("AdaptiveCard", root.path("type").asText());
        assertEquals("1.4", root.path("version").asText());

        JsonNode body = root.path("body");
        assertTrue(body.isArray() && body.size() >= 2);
        assertTrue(body.get(0).path("text").asText().contains("orders-bad-deployment-42"));

        JsonNode facts = body.get(1).path("facts");
        assertTrue(facts.isArray() && facts.size() >= 4);
        assertTrue(rendered.contains("Hikari pool starvation under load"));
        assertTrue(rendered.contains("https://github.com/my-org/orders-api/pull/42"));
    }

    @Test
    void capturingChannelAssertsGenericJsonStructure() throws Exception {
        CapturingNotificationChannel channel = new CapturingNotificationChannel("webhook", ChatFormat.GENERIC_JSON);
        channel.post(sample);

        assertEquals(1, channel.capturedFormatted().size());
        String rendered = channel.capturedFormatted().getFirst();
        JsonNode root = MAPPER.readTree(rendered);

        assertEquals("orders-bad-deployment-42", root.path("episodeId").asText());
        assertEquals("Hikari pool starvation under load", root.path("rootCause").asText());
        assertEquals("https://github.com/my-org/orders-api/pull/42", root.path("prLink").asText());
        assertEquals("SUCCESS", root.path("ciStatus").asText());
        assertEquals("RESOLVED", root.path("status").asText());
    }

    @Test
    void backwardCompatibleIncidentSummaryExtractsPrAndDefaults() {
        IncidentSummary legacy = new IncidentSummary(
                "incident-999",
                "Disk space 95% full",
                "PENDING",
                List.of("host=db-1", "pr=https://github.com/org/repo/pull/77")
        );

        assertEquals("incident-999", legacy.episodeId());
        assertEquals("PENDING", legacy.ciStatus());
        assertEquals("Disk space 95% full", legacy.rootCause());
        assertEquals("https://github.com/org/repo/pull/77", legacy.prLink());
    }

    private String loadFixture(String name) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/fixtures/chat/" + name)) {
            assertNotNull(is, "Fixture not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
