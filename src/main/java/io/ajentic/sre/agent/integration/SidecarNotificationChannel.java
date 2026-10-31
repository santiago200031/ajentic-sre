package io.ajentic.sre.agent.integration;

import io.ajentic.sre.connectors.SidecarUnavailableException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Outbound ChatOps notification channel delegating to the sidecar connectors service.
 */
public class SidecarNotificationChannel implements NotificationChannel {

    private final SidecarClient sidecarClient;

    public SidecarNotificationChannel(SidecarClient sidecarClient) {
        this.sidecarClient = sidecarClient;
    }

    @Override
    public String name() {
        return "sidecar";
    }

    @Override
    public void post(IncidentSummary summary) {
        try {
            sidecarClient.notify(
                    "sidecar",
                    "Incident: " + summary.incidentId() + " (" + summary.status() + ")",
                    summary.summary(),
                    "INFO");
        } catch (SidecarUnavailableException ignored) {
            // Fail closed/safe: unavailable sidecar should not crash the turn
        }
    }
}
