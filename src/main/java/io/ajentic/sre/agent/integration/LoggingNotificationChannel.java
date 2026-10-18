package io.ajentic.sre.agent.integration;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class LoggingNotificationChannel implements NotificationChannel {

    private static final Logger LOG = Logger.getLogger(LoggingNotificationChannel.class);

    @Override
    public void post(IncidentSummary summary) {
        LOG.infof("Incident %s [%s]: %s", summary.incidentId(), summary.status(), summary.summary());
    }
}
