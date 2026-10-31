package io.ajentic.sre.api.webhook;

import org.jboss.logging.Logger;

/**
 * Default channel: writes the report to the application log.
 */
public class LogNotificationChannel implements NotificationChannel {

    private static final Logger LOG = Logger.getLogger(LogNotificationChannel.class);

    @Override
    public void notify(AlertReport report) {
        LOG.infof("Alert report %s [%s/%s]: %s (session %s)",
                report.alert().fingerprint(),
                report.alert().source(),
                report.alert().service(),
                report.answer(),
                report.sessionId());
    }
}
