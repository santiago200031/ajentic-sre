package io.ajentic.sre.agent.integration;

public interface NotificationChannel {

    void post(IncidentSummary summary);
}
