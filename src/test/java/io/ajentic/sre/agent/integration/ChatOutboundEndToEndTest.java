package io.ajentic.sre.agent.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatOutboundEndToEndTest {

    private WireMockServer server;

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

    @BeforeEach
    void setup() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void teardown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void oneIncidentReportFansOutToAtLeastThreeChatProvidersBehindStubs() {
        server.stubFor(post(urlEqualTo("/slack")).willReturn(aResponse().withStatus(200).withBody("ok")));
        server.stubFor(post(urlEqualTo("/google-chat")).willReturn(aResponse().withStatus(200).withBody("{}")));
        server.stubFor(post(urlEqualTo("/discord")).willReturn(aResponse().withStatus(200).withBody("{}")));
        server.stubFor(post(urlEqualTo("/teams")).willReturn(aResponse().withStatus(200).withBody("{}")));

        Duration timeout = Duration.ofSeconds(2);
        SlackChannel slack = new SlackChannel(server.url("/slack"), null, null, null,
                timeout, 2, Duration.ofMillis(20), 3, Duration.ofSeconds(10), 10.0);
        GoogleChatChannel google = new GoogleChatChannel(server.url("/google-chat"),
                timeout, 2, Duration.ofMillis(20), 3, Duration.ofSeconds(10), 10.0);
        DiscordChannel discord = new DiscordChannel(server.url("/discord"),
                timeout, 2, Duration.ofMillis(20), 3, Duration.ofSeconds(10), 10.0);
        TeamsChannel teams = new TeamsChannel(server.url("/teams"),
                timeout, 2, Duration.ofMillis(20), 3, Duration.ofSeconds(10), 10.0);

        NotificationDispatcher dispatcher = new NotificationDispatcher(List.of(slack, google, discord, teams));
        dispatcher.postAsync(sample).join();

        server.verify(1, postRequestedFor(urlEqualTo("/slack")));
        server.verify(1, postRequestedFor(urlEqualTo("/google-chat")));
        server.verify(1, postRequestedFor(urlEqualTo("/discord")));
        server.verify(1, postRequestedFor(urlEqualTo("/teams")));

        var slackReqs = server.findAll(postRequestedFor(urlEqualTo("/slack")));
        assertTrue(slackReqs.getFirst().getBodyAsString().contains("*Incident:* orders-bad-deployment-42"));

        var teamsReqs = server.findAll(postRequestedFor(urlEqualTo("/teams")));
        assertTrue(teamsReqs.getFirst().getBodyAsString().contains("AdaptiveCard"));
    }

    @Test
    void blankConfigResultsInNoOutboundCalls() {
        SlackChannel blankSlack = new SlackChannel("", "", "", "",
                Duration.ofSeconds(1), 1, Duration.ofMillis(10), 3, Duration.ofSeconds(10), 10.0);
        GoogleChatChannel blankGoogle = new GoogleChatChannel("   ",
                Duration.ofSeconds(1), 1, Duration.ofMillis(10), 3, Duration.ofSeconds(10), 10.0);
        DiscordChannel blankDiscord = new DiscordChannel(null,
                Duration.ofSeconds(1), 1, Duration.ofMillis(10), 3, Duration.ofSeconds(10), 10.0);

        assertFalse(blankSlack.isConfigured());
        assertFalse(blankGoogle.isConfigured());
        assertFalse(blankDiscord.isConfigured());

        NotificationDispatcher dispatcher = new NotificationDispatcher(List.of(blankSlack, blankGoogle, blankDiscord));
        dispatcher.postAsync(sample).join();

        assertEquals(0, server.getAllServeEvents().size(), "Expected zero HTTP calls for blank channel configurations");
    }

    @Test
    void circuitBreakerTripsAfterFailuresAndFastFails() {
        server.stubFor(post(urlEqualTo("/failing"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        DiscordChannel channel = new DiscordChannel(server.url("/failing"),
                Duration.ofSeconds(1), 1, Duration.ofMillis(10), 2, Duration.ofSeconds(30), 10.0);

        // First call fails
        channel.post(sample);
        assertEquals(ChatResilience.CircuitState.CLOSED, channel.circuitBreaker().state());

        // Second failure reaches threshold 2 -> circuit breaker trips to OPEN
        channel.post(sample);
        assertEquals(ChatResilience.CircuitState.OPEN, channel.circuitBreaker().state());

        // Total calls to server so far: 2
        assertEquals(2, server.findAll(postRequestedFor(urlEqualTo("/failing"))).size());

        // Third call should fast-fail immediately without hitting the network
        channel.post(sample);
        assertEquals(ChatResilience.CircuitState.OPEN, channel.circuitBreaker().state());
        assertEquals(2, server.findAll(postRequestedFor(urlEqualTo("/failing"))).size(),
                "Circuit breaker should prevent network requests while OPEN");
    }

    @Test
    void retryWithJitterRetriesTransientErrorsAndSucceeds() {
        server.stubFor(post(urlEqualTo("/flaky"))
                .inScenario("Flaky")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable"))
                .willSetStateTo("Recovered"));

        server.stubFor(post(urlEqualTo("/flaky"))
                .inScenario("Flaky")
                .whenScenarioStateIs("Recovered")
                .willReturn(aResponse().withStatus(200).withBody("OK")));

        DiscordChannel channel = new DiscordChannel(server.url("/flaky"),
                Duration.ofSeconds(2), 3, Duration.ofMillis(10), 5, Duration.ofSeconds(10), 10.0);

        channel.post(sample);

        // Should have retried and succeeded on attempt 2
        server.verify(2, postRequestedFor(urlEqualTo("/flaky")));
        assertEquals(ChatResilience.CircuitState.CLOSED, channel.circuitBreaker().state());
    }

    @Test
    void telegramMattermostAndGenericSignedWebhookSucceedBehindStubs() {
        server.stubFor(post(urlEqualTo("/bot12345/sendMessage")).willReturn(aResponse().withStatus(200).withBody("{}")));
        server.stubFor(post(urlEqualTo("/mattermost")).willReturn(aResponse().withStatus(200).withBody("ok")));
        server.stubFor(post(urlEqualTo("/generic-hook")).willReturn(aResponse().withStatus(200).withBody("{}")));

        Duration timeout = Duration.ofSeconds(2);
        TelegramChannel telegram = new TelegramChannel("bot12345", "chat-789", server.url("/"),
                timeout, 1, Duration.ofMillis(10), 3, Duration.ofSeconds(10), 10.0);
        MattermostChannel mattermost = new MattermostChannel(server.url("/mattermost"),
                timeout, 1, Duration.ofMillis(10), 3, Duration.ofSeconds(10), 10.0);
        GenericWebhookChannel webhook = new GenericWebhookChannel(server.url("/generic-hook"), "test-secret-42",
                timeout, 1, Duration.ofMillis(10), 3, Duration.ofSeconds(10), 10.0);

        NotificationDispatcher dispatcher = new NotificationDispatcher(List.of(telegram, mattermost, webhook));
        dispatcher.postAsync(sample).join();

        server.verify(1, postRequestedFor(urlEqualTo("/bot12345/sendMessage")));
        server.verify(1, postRequestedFor(urlEqualTo("/mattermost")));
        server.verify(1, postRequestedFor(urlEqualTo("/generic-hook"))
                .withHeader("X-Ajentic-Signature", WireMock.matching("sha256=[0-9a-f]{64}")));
    }

    @Test
    void tokenBucketRateLimiterLimitsExcessRequests() {
        // Capacity = 3, refill rate = 1 token/sec
        ChatResilience.TokenBucketRateLimiter limiter = new ChatResilience.TokenBucketRateLimiter(3.0, 1.0);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        // 4th request must be rate limited
        assertFalse(limiter.tryAcquire());
    }
}
