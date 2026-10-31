package io.ajentic.sre.agent.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test capturing notification channel that records posted summaries and their formatted outputs.
 */
public class CapturingNotificationChannel implements NotificationChannel {

    private final String channelName;
    private final ChatFormat format;
    private final List<IncidentSummary> capturedSummaries = new ArrayList<>();
    private final List<String> capturedFormatted = new ArrayList<>();

    public CapturingNotificationChannel(String channelName, ChatFormat format) {
        this.channelName = channelName;
        this.format = format;
    }

    @Override
    public String name() {
        return channelName;
    }

    @Override
    public synchronized void post(IncidentSummary summary) {
        capturedSummaries.add(summary);
        if (format != null) {
            capturedFormatted.add(format.format(summary));
        }
    }

    public synchronized List<IncidentSummary> capturedSummaries() {
        return Collections.unmodifiableList(capturedSummaries);
    }

    public synchronized List<String> capturedFormatted() {
        return Collections.unmodifiableList(capturedFormatted);
    }

    public synchronized void clear() {
        capturedSummaries.clear();
        capturedFormatted.clear();
    }
}
