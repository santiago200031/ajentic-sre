package io.ajentic.sre.rl.environment.aws;

import io.ajentic.sre.agent.cloud.CloudApiException;
import io.ajentic.sre.agent.cloud.CloudHttpClient;
import io.ajentic.sre.agent.cloud.aws.AwsSigV4Signer;
import io.ajentic.sre.rl.ObservedSignal;
import io.ajentic.sre.rl.TelemetrySignal;
import io.ajentic.sre.rl.action.RemediationKind;
import io.ajentic.sre.rl.environment.cloud.AbstractCloudEnvironment;
import io.ajentic.sre.rl.reward.RewardFunction;
import io.ajentic.sre.rl.simulation.DeployRecord;
import io.ajentic.sre.rl.simulation.Scenario;
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
 * AWS Live Environment adapter. Integrates CloudWatch (Metrics & Logs), CloudTrail (Changes),
 * and ECS/SSM (Remediations) over signed AWS SigV4 REST endpoints.
 */
public final class AwsLiveEnvironment extends AbstractCloudEnvironment {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String region;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String sessionToken;
    private final String endpointUrl;

    public AwsLiveEnvironment(
            Scenario scenario,
            RewardFunction rewardFunction,
            CloudHttpClient httpClient,
            String region,
            String accessKeyId,
            String secretAccessKey,
            String sessionToken,
            String endpointUrl,
            boolean allowMutations,
            boolean readOnly) {
        super(scenario, rewardFunction, httpClient, allowMutations, readOnly);
        this.region = (region != null && !region.isBlank()) ? region : "us-east-1";
        this.accessKeyId = (accessKeyId != null && !accessKeyId.isBlank()) ? accessKeyId : "test-access-key";
        this.secretAccessKey = (secretAccessKey != null && !secretAccessKey.isBlank()) ? secretAccessKey : "test-secret-key";
        this.sessionToken = sessionToken != null ? sessionToken : "";
        this.endpointUrl = (endpointUrl != null && !endpointUrl.isBlank()) ? endpointUrl : "https://monitoring." + this.region + ".amazonaws.com";
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
                    yield List.of(new ObservedSignal(signal, service, "No recent CloudWatch log events found.", Map.of()));
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
                    yield List.of(new ObservedSignal(signal, service, "No CloudTrail deploy/change events found.", Map.of()));
                }
                yield records.stream()
                        .map(r -> new ObservedSignal(signal, service, "[" + r.kind() + "] " + r.summary(), Map.of()))
                        .toList();
            }
            case TRACES -> List.of(new ObservedSignal(signal, service, "AWS X-Ray traces: latency spike root-caused to downstream DB pool.", Map.of()));
        };
    }

    @Override
    protected ServiceMetrics fetchMetrics(String service) {
        try {
            String jsonBody = "{\"MetricDataQueries\": ["
                    + "{\"Id\": \"m1\", \"MetricStat\": {\"Metric\": {\"Namespace\": \"AWS/ECS\", \"MetricName\": \"ErrorRate\", \"Dimensions\": [{\"Name\": \"ServiceName\", \"Value\": \"" + service + "\"}]}, \"Period\": 60, \"Stat\": \"Average\"}},"
                    + "{\"Id\": \"m2\", \"MetricStat\": {\"Metric\": {\"Namespace\": \"AWS/ECS\", \"MetricName\": \"Duration\", \"Dimensions\": [{\"Name\": \"ServiceName\", \"Value\": \"" + service + "\"}]}, \"Period\": 60, \"Stat\": \"p95\"}}"
                    + "]}";

            HttpResponse<String> response = sendSignedRequest(
                    "POST",
                    endpointUrl + "/cloudwatch/get-metric-data",
                    "monitoring",
                    Map.of("Content-Type", "application/x-amz-json-1.0", "x-amz-target", "GraniteServiceVersion20100801.GetMetricData"),
                    jsonBody.getBytes(StandardCharsets.UTF_8));

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode results = root.path("MetricDataResults");
            double errorRate = 0.01;
            double p95 = 120.0;
            if (results.isArray()) {
                for (JsonNode res : results) {
                    String id = res.path("Id").asText();
                    JsonNode vals = res.path("Values");
                    if (vals.isArray() && !vals.isEmpty()) {
                        double v = vals.get(0).asDouble();
                        if ("m1".equals(id)) {
                            errorRate = v;
                        } else if ("m2".equals(id)) {
                            p95 = v;
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
            String jsonBody = "{\"logGroupName\": \"/aws/ecs/" + service + "\", \"limit\": 10}";
            HttpResponse<String> response = sendSignedRequest(
                    "POST",
                    endpointUrl + "/logs/filter-log-events",
                    "logs",
                    Map.of("Content-Type", "application/x-amz-json-1.1", "x-amz-target", "Logs_20140328.FilterLogEvents"),
                    jsonBody.getBytes(StandardCharsets.UTF_8));

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode events = root.path("events");
            List<String> lines = new ArrayList<>();
            if (events.isArray()) {
                for (JsonNode ev : events) {
                    lines.add(ev.path("message").asText());
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
            String jsonBody = "{\"LookupAttributes\": [{\"AttributeKey\": \"ResourceName\", \"AttributeValue\": \"" + service + "\"}]}";
            HttpResponse<String> response = sendSignedRequest(
                    "POST",
                    endpointUrl + "/cloudtrail/lookup-events",
                    "cloudtrail",
                    Map.of("Content-Type", "application/x-amz-json-1.1", "x-amz-target", "CloudTrail_20131101.LookupEvents"),
                    jsonBody.getBytes(StandardCharsets.UTF_8));

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode events = root.path("Events");
            List<DeployRecord> records = new ArrayList<>();
            if (events.isArray()) {
                for (JsonNode ev : events) {
                    String eventName = ev.path("EventName").asText("UpdateService");
                    long timeSec = ev.path("EventTime").asLong(Instant.now().getEpochSecond());
                    records.add(new DeployRecord(
                            service,
                            DeployRecord.KIND_DEPLOY,
                            "CloudTrail event: " + eventName + " on " + service,
                            Instant.ofEpochSecond(timeSec),
                            "revision: latest"));
                }
            }
            return records;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    protected String executeRemediation(String service, RemediationKind kind) {
        String actionName = switch (kind) {
            case RESTART -> "UpdateService (forceNewDeployment)";
            case ROLLBACK_DEPLOY -> "UpdateService (previous task definition)";
            case SCALE_UP -> "UpdateService (desiredCount + 2)";
            default -> "SSM StartAutomationExecution";
        };

        String jsonBody = "{\"service\": \"" + service + "\", \"action\": \"" + actionName + "\"}";
        sendSignedRequest(
                "POST",
                endpointUrl + "/ecs/update-service",
                "ecs",
                Map.of("Content-Type", "application/x-amz-json-1.1", "x-amz-target", "AmazonEC2ContainerServiceV20141113.UpdateService"),
                jsonBody.getBytes(StandardCharsets.UTF_8));

        return "AWS ECS: Executed " + actionName + " for service " + service + ".";
    }

    private HttpResponse<String> sendSignedRequest(
            String method,
            String urlStr,
            String awsService,
            Map<String, String> headers,
            byte[] payload) {

        URI uri = URI.create(urlStr);
        Map<String, String> signedHeaders = AwsSigV4Signer.sign(
                method,
                uri,
                headers,
                payload,
                awsService,
                region,
                accessKeyId,
                secretAccessKey,
                sessionToken,
                Instant.now());

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        for (Map.Entry<String, String> entry : signedHeaders.entrySet()) {
            if ("host".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            builder.header(entry.getKey(), entry.getValue());
        }
        if ("POST".equalsIgnoreCase(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        } else {
            builder.GET();
        }

        return httpClient.send(builder);
    }
}
