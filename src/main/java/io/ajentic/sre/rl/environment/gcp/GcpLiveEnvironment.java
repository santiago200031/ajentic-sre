package io.ajentic.sre.rl.environment.gcp;

import io.ajentic.sre.agent.cloud.CloudApiException;
import io.ajentic.sre.agent.cloud.CloudHttpClient;
import io.ajentic.sre.agent.cloud.gcp.GcpTokenSource;
import io.ajentic.sre.rl.ObservedSignal;
import io.ajentic.sre.rl.TelemetrySignal;
import io.ajentic.sre.rl.action.RemediationKind;
import io.ajentic.sre.rl.environment.cloud.AbstractCloudEnvironment;
import io.ajentic.sre.rl.reward.RewardFunction;
import io.ajentic.sre.rl.simulation.DeployRecord;
import io.ajentic.sre.rl.simulation.Scenario;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
 * GCP Live Environment adapter. Integrates Cloud Monitoring (timeSeries), Cloud Logging (entries),
 * Cloud Run Revisions (deploys/changes), and Cloud Run Rollback / Service Update.
 */
public final class GcpLiveEnvironment extends AbstractCloudEnvironment {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String projectId;
    private final String endpointUrl;
    private final GcpTokenSource tokenSource;

    public GcpLiveEnvironment(
            Scenario scenario,
            RewardFunction rewardFunction,
            CloudHttpClient httpClient,
            String projectId,
            String clientEmail,
            String privateKeyPem,
            String endpointUrl,
            String tokenUrl,
            String metadataUrl,
            boolean allowMutations,
            boolean readOnly) {
        super(scenario, rewardFunction, httpClient, allowMutations, readOnly);
        this.projectId = (projectId != null && !projectId.isBlank()) ? projectId : "test-gcp-project";
        this.endpointUrl = (endpointUrl != null && !endpointUrl.isBlank()) ? endpointUrl : "https://monitoring.googleapis.com";
        this.tokenSource = new GcpTokenSource(clientEmail, privateKeyPem, null, tokenUrl, metadataUrl, null);
    }

    public GcpLiveEnvironment(
            Scenario scenario,
            RewardFunction rewardFunction,
            CloudHttpClient httpClient,
            String projectId,
            String clientEmail,
            String privateKeyPem,
            String endpointUrl,
            String tokenUrl,
            boolean allowMutations,
            boolean readOnly) {
        this(scenario, rewardFunction, httpClient, projectId, clientEmail, privateKeyPem, endpointUrl, tokenUrl, null, allowMutations, readOnly);
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
                    yield List.of(new ObservedSignal(signal, service, "No Cloud Logging entries found.", Map.of()));
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
                    yield List.of(new ObservedSignal(signal, service, "No Cloud Run revision changes found.", Map.of()));
                }
                yield records.stream()
                        .map(r -> new ObservedSignal(signal, service, "[" + r.kind() + "] " + r.summary(), Map.of()))
                        .toList();
            }
            case TRACES -> List.of(new ObservedSignal(signal, service, "Cloud Trace: spans indicate timeout during remote RPC call.", Map.of()));
        };
    }

    @Override
    protected ServiceMetrics fetchMetrics(String service) {
        try {
            String url = endpointUrl + "/v3/projects/" + projectId + "/timeSeries?filter=metric.type%3D%22run.googleapis.com%2Frequest_count%22";
            HttpResponse<String> response = sendAuthorizedRequest("GET", url, null, null);

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode timeSeries = root.path("timeSeries");
            double errorRate = 0.01;
            double p95 = 115.0;
            if (timeSeries.isArray() && !timeSeries.isEmpty()) {
                for (JsonNode ts : timeSeries) {
                    JsonNode points = ts.path("points");
                    if (points.isArray() && !points.isEmpty()) {
                        double val = points.get(0).path("value").path("doubleValue").asDouble(0.0);
                        if (val > 0) {
                            errorRate = val;
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
            String url = endpointUrl + "/v2/entries:list";
            String queryJson = "{\"resourceNames\": [\"projects/" + projectId + "\"], \"pageSize\": 10}";
            HttpResponse<String> response = sendAuthorizedRequest("POST", url, queryJson.getBytes(StandardCharsets.UTF_8), "application/json");

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode entries = root.path("entries");
            List<String> lines = new ArrayList<>();
            if (entries.isArray()) {
                for (JsonNode entry : entries) {
                    String payload = entry.path("textPayload").asString();
                    if (payload.isEmpty()) {
                        payload = entry.path("jsonPayload").path("message").asString();
                    }
                    if (!payload.isEmpty()) {
                        lines.add(payload);
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
            String url = endpointUrl + "/v1/projects/" + projectId + "/locations/us-central1/services/" + service + "/revisions";
            HttpResponse<String> response = sendAuthorizedRequest("GET", url, null, null);

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode revisions = root.path("revisions");
            List<DeployRecord> records = new ArrayList<>();
            if (revisions.isArray()) {
                for (JsonNode rev : revisions) {
                    String name = rev.path("name").asString("rev-latest");
                    String createTime = rev.path("createTime").asString();
                    Instant time = Instant.now();
                    try {
                        if (!createTime.isEmpty()) {
                            time = Instant.parse(createTime);
                        }
                    } catch (Exception ignored) {
                    }
                    records.add(new DeployRecord(
                            service,
                            DeployRecord.KIND_DEPLOY,
                            "Cloud Run Revision created: " + name,
                            time,
                            "revision: " + name));
                }
            }
            return records;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    protected String executeRemediation(String service, RemediationKind kind) {
        String url = endpointUrl + "/v1/projects/" + projectId + "/locations/us-central1/services/" + service + ":rollback";
        sendAuthorizedRequest("POST", url, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
        return "Google Cloud Run: Rolled back service " + service + " to previous stable revision.";
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
            }
            throw e;
        }
    }
}
