package io.ajentic.sre.api.webhook;

import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.session.SessionService;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ScenarioRepository;
import io.ajentic.sre.rl.simulation.UnknownScenarioException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Turns an inbound alert into an agent investigation. The alert's service is matched against the built-in
 * scenarios (falling back to the configured default), one agent session runs the investigation to completion, and
 * the report is fanned out to every notification channel. Repeats of the same fingerprint are coalesced onto the
 * original report, and a semaphore bounds how many investigations run concurrently.
 */
@ApplicationScoped
public class AlertIntakeService {

    private final ScenarioRepository scenarios;
    private final SessionService sessions;
    private final List<NotificationChannel> channels;
    private final Map<String, AlertReport> reports = new ConcurrentHashMap<>();
    private final Semaphore inFlight;
    private final String defaultScenarioId;

    @Inject
    public AlertIntakeService(
            ScenarioRepository scenarios,
            SessionService sessions,
            @ConfigProperty(name = "ajentic.webhooks.notification-url") Optional<String> notificationUrl,
            @ConfigProperty(name = "ajentic.webhooks.max-concurrent", defaultValue = "4") int maxConcurrent,
            @ConfigProperty(name = "ajentic.webhooks.default-scenario", defaultValue = "orders-bad-deployment") String defaultScenarioId) {
        this.scenarios = scenarios;
        this.sessions = sessions;
        List<NotificationChannel> channels = new ArrayList<>();
        channels.add(new LogNotificationChannel());
        if (notificationUrl.isPresent()) {
            channels.add(new WebhookNotificationChannel(notificationUrl.get()));
        }
        this.channels = List.copyOf(channels);
        this.inFlight = new Semaphore(maxConcurrent);
        this.defaultScenarioId = defaultScenarioId;
    }

    public IntakeResult intake(Alert alert) {
        AlertReport existing = reports.get(alert.fingerprint());
        if (existing != null) {
            return new IntakeResult(existing, existing.scenarioId(), true);
        }
        if (!inFlight.tryAcquire()) {
            throw new WebhookException(429, "Too many investigations in flight; retry later.");
        }
        try {
            String scenarioId = scenarioFor(alert.service()).orElse(defaultScenarioId);
            if (scenarios.byId(scenarioId).isEmpty()) {
                throw new UnknownScenarioException(scenarioId);
            }
            AgentSession session = sessions.create(AgentSessionConfig.forScenario(scenarioId));
            String answer = session.chat(
                    "Alert from " + alert.source() + ": " + alert.summary()
                            + " on service " + alert.service() + ". Identify the root cause and recommend remediation.")
                    .answer();
            AlertReport report = new AlertReport(alert, scenarioId, session.id(), answer, Instant.now(), Instant.now(), false);
            reports.put(alert.fingerprint(), report);
            channels.forEach(channel -> channel.notify(report));
            return new IntakeResult(report, scenarioId, false);
        } finally {
            inFlight.release();
        }
    }

    public Optional<AlertReport> report(String fingerprint) {
        return Optional.ofNullable(reports.get(fingerprint));
    }

    public Map<String, AlertReport> reports() {
        return Map.copyOf(reports);
    }

    private Optional<String> scenarioFor(String service) {
        if (service == null || service.isBlank()) {
            return Optional.empty();
        }
        return scenarios.all().stream()
                .filter(scenario -> scenario.services().stream().anyMatch(spec -> spec.name().equals(service)))
                .map(Scenario::id)
                .findFirst();
    }

    /**
     * One accepted intake: the stored report plus the scenario the agent investigated.
     */
    public record IntakeResult(AlertReport report, String scenarioId, boolean duplicate) {
    }
}
