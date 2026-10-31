package io.ajentic.sre.connectors;

import java.util.List;
import java.util.Map;

public final class SidecarDto {

    private SidecarDto() {
    }

    public record TelemetryRequest(String service, String signal) {
    }

    public record SignalItem(String signal, String service, String summary, Map<String, Object> attributes) {
    }

    public record TelemetryResponse(List<SignalItem> signals, double errorRate, double p95LatencyMs) {
    }

    public record ChangesRequest(String service, int windowMinutes) {
    }

    public record ChangeItem(String service, String kind, String summary, String occurredAt, String diff) {
    }

    public record ChangesResponse(List<ChangeItem> changes) {
    }

    public record RemediationRequest(String service, String remediation, String actor) {
    }

    public record RemediationResponse(boolean success, String message) {
    }

    public record NotifyRequest(String title, String body, String severity) {
    }

    public record NotifyResponse(boolean success, String message) {
    }
}
