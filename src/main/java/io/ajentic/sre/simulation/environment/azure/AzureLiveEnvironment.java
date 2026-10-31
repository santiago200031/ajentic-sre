package io.ajentic.sre.simulation.environment.azure;

import io.ajentic.sre.agent.cloud.CloudApiException;
import io.ajentic.sre.agent.cloud.CloudHttpClient;
import io.ajentic.sre.agent.cloud.azure.AzureTokenSource;
import io.ajentic.sre.simulation.ObservedSignal;
import io.ajentic.sre.simulation.TelemetrySignal;
import io.ajentic.sre.simulation.action.RemediationKind;
import io.ajentic.sre.simulation.environment.cloud.AbstractCloudEnvironment;
import io.ajentic.sre.simulation.scorecard.Scorecard;
import io.ajentic.sre.simulation.DeployRecord;
import io.ajentic.sre.simulation.Scenario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Azure Live Environment adapter. Integrates Azure Monitor Metrics, Log Analytics,
 * Activity Log (events/changes), and Container Apps / App Service remediations.
 */
public final class AzureLiveEnvironment extends AbstractCloudEnvironment {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String subscriptionId;
    private final String resourceGroup;
    private final String workspaceId;
    private final String endpointUrl;
    private final AzureTokenSource tokenSource;

    public AzureLiveEnvironment(
            Scenario scenario,
            Scorecard rewardFunction,
            CloudHttpClient httpClient,
            String tenantId,
            String clientId,
            String clientSecret,
            String subscriptionId,
            String resourceGroup,
            String workspaceId,
            String endpointUrl,
            String tokenUrl,
            boolean allowMutations,
            boolean readOnly) {
        super(scenario, rewardFunction, httpClient, allowMutations, readOnly);
        this.subscriptionId = (subscriptionId != null && !subscriptionId.isBlank()) ? subscriptionId : "sub-test-123";
        this.resourceGroup = (resourceGroup != null && !resourceGroup.isBlank()) ? resourceGroup : "rg-test";
        this.workspaceId = (workspaceId != null && !workspaceId.isBlank()) ? workspaceId : "ws-test-123";
        this.endpointUrl = (endpointUrl != null && !endpointUrl.isBlank()) ? endpointUrl : "https://management.azure.com";
        this.tokenSource = new AzureTokenSource(tenantId, clientId, clientSecret, null, tokenUrl, null, null);
    }

    @Override
    public synchronized List<ObservedSignal> signals(TelemetrySignal signal, String service) {
        return switch (signal) {
            case METRICS -> {
                ServiceMetrics m = fetchMetrics(service);
                if (m == null) {
                    m = new ServiceMetrics(0.0, 0.0);
                }
                yield List.of(new ObservedSignal(
                        signal,
                        service,
                        "errorRate=%.4f p95LatencyMs=%.1f".formatted(m.errorRate(), m.p95LatencyMs()),
                        Map.of("errorRate", m.errorRate(), "p95LatencyMs", m.p95LatencyMs())));
            }
            case LOGS -> {
                List<String> lines = fetchLogs(service);
                if (lines.isEmpty()) {
                    yield List.of(new ObservedSignal(signal, service, "No Log Analytics events found.", Map.of()));
                }
                List<ObservedSignal> res = new ArrayList<>();
                for (String line : lines) {
                    res.add(new ObservedSignal(signal, service, line, Map.of()));
                }
                yield res;
            }
            case DEPLOYS -> {
                List<DeployRecord> records = fetchRecentChanges(service, 1440);
                if (records.isEmpty()) {
                    yield List.of(new ObservedSignal(signal, service, "No Azure Activity Log deploy/change events found.", Map.of()));
                }
                yield records.stream()
                        .map(r -> new ObservedSignal(signal, service, "[" + r.kind() + "] " + r.summary(), Map.of()))
                        .toList();
            }
            case TRACES -> List.of(new ObservedSignal(signal, service, "Azure Application Insights trace: request failed with 503.", Map.of()));
        };
    }

    @Override
    protected ServiceMetrics fetchMetrics(String service) {
        try {
            String path = endpointUrl + "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                    + "/providers/Microsoft.App/containerApps/" + service
                    + "/providers/microsoft.insights/metrics?api-version=2018-01-01&metricnames=Requests,Errors";

            HttpResponse<String> response = sendAuthorizedRequest("GET", path, null, null);
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode valueArr = root.path("value");
            double errorRate = 0.01;
            double p95 = 110.0;
            if (valueArr.isArray()) {
                for (JsonNode metricNode : valueArr) {
                    String name = metricNode.path("name").path("value").asText();
                    JsonNode timeseries = metricNode.path("timeseries");
                    if (timeseries.isArray() && !timeseries.isEmpty()) {
                        JsonNode data = timeseries.get(0).path("data");
                        if (data.isArray() && !data.isEmpty()) {
                            double val = data.get(data.size() - 1).path("average").asDouble();
                            if ("Errors".equalsIgnoreCase(name)) {
                                errorRate = val;
                            } else if ("Requests".equalsIgnoreCase(name) && val > 0) {
                                p95 = val;
                            }
                        }
                    }
                }
            }
            return new ServiceMetrics(errorRate, p95);
        } catch (CloudApiException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> fetchLogs(String service) {
        try {
            String url = endpointUrl + "/v1/workspaces/" + workspaceId + "/query";
            String queryJson = "{\"query\": \"ContainerLog | where ContainerName == '" + service + "' | project LogEntry\"}";
            HttpResponse<String> response = sendAuthorizedRequest("POST", url, queryJson.getBytes(StandardCharsets.UTF_8), "application/json");

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode rows = root.path("tables").path(0).path("rows");
            List<String> lines = new ArrayList<>();
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    if (row.isArray() && !row.isEmpty()) {
                        lines.add(row.get(0).asText());
                    }
                }
            }
            return lines;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    protected List<DeployRecord> fetchRecentChanges(String service, int windowMinutes) {
        try {
            String url = endpointUrl + "/subscriptions/" + subscriptionId + "/providers/microsoft.insights/eventtypes/management/values?api-version=2014-04-01";
            HttpResponse<String> response = sendAuthorizedRequest("GET", url, null, null);

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode values = root.path("value");
            List<DeployRecord> records = new ArrayList<>();
            if (values.isArray()) {
                for (JsonNode item : values) {
                    String operationName = item.path("operationName").path("value").asText("Microsoft.App/containerApps/write");
                    String eventTimestamp = item.path("eventTimestamp").asText();
                    Instant time = Instant.now();
                    try {
                        if (!eventTimestamp.isEmpty()) {
                            time = Instant.parse(eventTimestamp);
                        }
                    } catch (Exception ignored) {
                    }
                    records.add(new DeployRecord(
                            service,
                            DeployRecord.KIND_DEPLOY,
                            "Azure Activity Log: " + operationName + " on " + service,
                            time,
                            "resource: " + service));
                }
            }
            return records;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    protected String executeRemediation(String service, RemediationKind kind) {
        String operation = (kind == RemediationKind.SCALE_UP) ? "scale" : "restart";
        String url = endpointUrl + "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                + "/providers/Microsoft.App/containerApps/" + service + "/" + operation + "?api-version=2022-03-01";

        sendAuthorizedRequest("POST", url, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
        return "Azure Container Apps: Executed " + operation + " for " + service + ".";
    }

    private HttpResponse<String> sendAuthorizedRequest(String method, String url, byte[] body, String contentType) {
        String token = tokenSource.getAccessToken();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token);

        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }

        if ("POST".equalsIgnoreCase(method)) {
            builder.POST(body != null ? HttpRequest.BodyPublishers.ofByteArray(body) : HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }

        try {
            return httpClient.send(builder);
        } catch (CloudApiException e) {
            if (e.statusCode() == 401) {
                tokenSource.invalidate();
                String freshToken = tokenSource.getAccessToken();
                builder.setHeader("Authorization", "Bearer " + freshToken);
                return httpClient.send(builder);
            }
            throw e;
        }
    }
}
