package io.ajentic.sre.api.webhook;

/**
 * Publishes an alert report somewhere a human or system can see it. Intake always succeeds even if a channel fails:
 * notification is best-effort.
 */
public interface NotificationChannel {

    void notify(AlertReport report);
}
