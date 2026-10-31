package io.ajentic.sre.agent.integration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Composite dispatcher for notification channels.
 * Selected by `ajentic.notifications.channel` (comma-separated fan-out).
 * Dispatches asynchronously on virtual threads so outbound latency never stalls the agent turn.
 */
@ApplicationScoped
public class NotificationDispatcher implements NotificationChannel {

    private static final Logger LOG = Logger.getLogger(NotificationDispatcher.class);

    private final List<NotificationChannel> channels = new CopyOnWriteArrayList<>();

    public NotificationDispatcher() {
        // Default constructor for CDI proxy
    }

    @Inject
    public NotificationDispatcher(
            @ConfigProperty(name = "ajentic.notifications.channel", defaultValue = "log") String channelSelection,
            @ConfigProperty(name = "ajentic.notifications.timeout", defaultValue = "5s") Duration timeout,
            @ConfigProperty(name = "ajentic.notifications.max-retries", defaultValue = "3") int maxRetries,
            @ConfigProperty(name = "ajentic.notifications.retry-backoff", defaultValue = "100ms") Duration retryBackoff,
            @ConfigProperty(name = "ajentic.notifications.circuit-breaker-threshold", defaultValue = "5") int circuitThreshold,
            @ConfigProperty(name = "ajentic.notifications.circuit-breaker-cooldown", defaultValue = "30s") Duration circuitCooldown,
            @ConfigProperty(name = "ajentic.notifications.rate-limit-per-second", defaultValue = "10.0") double rateLimitPerSecond,
            @ConfigProperty(name = "ajentic.notifications.slack.webhook-url") Optional<String> slackWebhookUrl,
            @ConfigProperty(name = "ajentic.notifications.slack.token") Optional<String> slackToken,
            @ConfigProperty(name = "ajentic.notifications.slack.channel") Optional<String> slackChannel,
            @ConfigProperty(name = "ajentic.notifications.slack.api-url") Optional<String> slackApiUrl,
            @ConfigProperty(name = "ajentic.notifications.teams.webhook-url") Optional<String> teamsWebhookUrl,
            @ConfigProperty(name = "ajentic.notifications.google-chat.webhook-url") Optional<String> googleChatWebhookUrl,
            @ConfigProperty(name = "ajentic.notifications.discord.webhook-url") Optional<String> discordWebhookUrl,
            @ConfigProperty(name = "ajentic.notifications.telegram.bot-token") Optional<String> telegramBotToken,
            @ConfigProperty(name = "ajentic.notifications.telegram.chat-id") Optional<String> telegramChatId,
            @ConfigProperty(name = "ajentic.notifications.telegram.api-url") Optional<String> telegramApiUrl,
            @ConfigProperty(name = "ajentic.notifications.mattermost.webhook-url") Optional<String> mattermostWebhookUrl,
            @ConfigProperty(name = "ajentic.notifications.webhook.url") Optional<String> genericWebhookUrl,
            @ConfigProperty(name = "ajentic.notifications.webhook.secret") Optional<String> genericWebhookSecret,
            jakarta.enterprise.inject.Instance<SidecarClient> sidecarClientInstance) {

        List<NotificationChannel> configured = new ArrayList<>();
        String[] selections = channelSelection.split(",");
        for (String sel : selections) {
            String name = sel.trim().toLowerCase(Locale.ROOT);
            if (name.isBlank()) {
                continue;
            }
            switch (name) {
                case "log" -> configured.add(new LoggingNotificationChannel());
                case "sidecar" -> {
                    if (sidecarClientInstance != null && sidecarClientInstance.isResolvable()) {
                        configured.add(new SidecarNotificationChannel(sidecarClientInstance.get()));
                    }
                }
                case "slack" -> configured.add(new SlackChannel(
                        slackWebhookUrl.orElse(null),
                        slackToken.orElse(null),
                        slackChannel.orElse(null),
                        slackApiUrl.orElse(null),
                        timeout, maxRetries, retryBackoff, circuitThreshold, circuitCooldown, rateLimitPerSecond));
                case "teams" -> configured.add(new TeamsChannel(
                        teamsWebhookUrl.orElse(null),
                        timeout, maxRetries, retryBackoff, circuitThreshold, circuitCooldown, rateLimitPerSecond));
                case "google-chat", "google" -> configured.add(new GoogleChatChannel(
                        googleChatWebhookUrl.orElse(null),
                        timeout, maxRetries, retryBackoff, circuitThreshold, circuitCooldown, rateLimitPerSecond));
                case "discord" -> configured.add(new DiscordChannel(
                        discordWebhookUrl.orElse(null),
                        timeout, maxRetries, retryBackoff, circuitThreshold, circuitCooldown, rateLimitPerSecond));
                case "telegram" -> configured.add(new TelegramChannel(
                        telegramBotToken.orElse(null),
                        telegramChatId.orElse(null),
                        telegramApiUrl.orElse(null),
                        timeout, maxRetries, retryBackoff, circuitThreshold, circuitCooldown, rateLimitPerSecond));
                case "mattermost" -> configured.add(new MattermostChannel(
                        mattermostWebhookUrl.orElse(null),
                        timeout, maxRetries, retryBackoff, circuitThreshold, circuitCooldown, rateLimitPerSecond));
                case "webhook" -> configured.add(new GenericWebhookChannel(
                        genericWebhookUrl.orElse(null),
                        genericWebhookSecret.orElse(null),
                        timeout, maxRetries, retryBackoff, circuitThreshold, circuitCooldown, rateLimitPerSecond));
                default -> LOG.warnf("Unknown notification channel: %s", name);
            }
        }
        this.channels.addAll(configured);
    }

    public NotificationDispatcher(List<NotificationChannel> channels) {
        this.channels.addAll(channels);
    }

    public void registerChannel(NotificationChannel channel) {
        channels.add(channel);
    }

    public void removeChannel(NotificationChannel channel) {
        channels.remove(channel);
    }

    public List<NotificationChannel> channels() {
        return Collections.unmodifiableList(channels);
    }

    @Override
    public String name() {
        return "dispatcher";
    }

    @Override
    public void post(IncidentSummary summary) {
        for (NotificationChannel channel : channels) {
            Thread.ofVirtual().start(() -> {
                try {
                    channel.post(summary);
                } catch (Throwable t) {
                    LOG.warnf("Failed to post notification to %s: %s", channel.name(), t.getMessage());
                }
            });
        }
    }

    /**
     * Synchronous fan-out dispatch, useful for testing or blocking calls.
     */
    public CompletableFuture<Void> postAsync(IncidentSummary summary) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (NotificationChannel channel : channels) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    channel.post(summary);
                } catch (Throwable t) {
                    LOG.warnf("Failed to post notification to %s: %s", channel.name(), t.getMessage());
                }
            }, runnable -> Thread.ofVirtual().start(runnable)));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
