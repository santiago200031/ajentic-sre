package io.ajentic.sre.agent.integration;

import org.jboss.logging.Logger;

/**
 * Standard log-based notification channel.
 */
public class LoggingNotificationChannel implements NotificationChannel {

    private static final Logger LOG = Logger.getLogger(LoggingNotificationChannel.class);

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void post(IncidentSummary summary) {
        LOG.infof("Incident %s [%s]: %s", summary.episodeId(), summary.status(), summary.summary());
    }
}
