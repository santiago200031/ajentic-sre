package io.ajentic.sre.agent.alerts;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure, unit-testable normalizer converting multi-cloud payloads (CloudWatch, Azure Monitor, Alertmanager,
 * or direct JSON) into standardized {@link NormalizedAlert} records.
 */
@ApplicationScoped
public class AlertNormalizer {

    private final Optional<String> defaultRepo;
    private final ObjectMapper mapper;

    public AlertNormalizer() {
        this(Optional.empty(), new ObjectMapper());
    }

    @Inject
    public AlertNormalizer(
            @ConfigProperty(name = "ajentic.github.default-repo") Optional<String> defaultRepo,
            ObjectMapper mapper) {
        this.defaultRepo = defaultRepo;
        this.mapper = mapper;
    }

    public NormalizedAlert normalize(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            String repo = defaultRepo.filter(r -> !r.isBlank()).orElse(null);
            return new NormalizedAlert(AlertSeverity.INFO, null, repo, "main", null, null, null, hash(""));
        }

        // 1. Unwrap SNS notification if present
        Map<String, Object> body = payload;
        if (payload.containsKey("TopicArn") && payload.containsKey("Message")) {
            Object msg = payload.get("Message");
            if (msg instanceof String str && str.trim().startsWith("{")) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = mapper.readValue(str, Map.class);
                    body = parsed;
                } catch (Exception ignored) {
                }
            }
        }

        // 2. Identify provider shape
        if (body.containsKey("alerts") && body.get("alerts") instanceof List<?> list && !list.isEmpty()) {
            return fromAlertmanager(body, list);
        } else if (body.containsKey("data") && body.get("data") instanceof Map<?, ?> data
                && data.containsKey("essentials")) {
            return fromAzureMonitor(body, data);
        } else if (body.containsKey("AlarmName") || body.containsKey("NewStateReason")) {
            return fromCloudWatch(body);
        } else {
            return fromDirectJson(body);
        }
    }

    private NormalizedAlert fromAlertmanager(Map<String, Object> root, List<?> list) {
        Map<?, ?> first = list.getFirst() instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> labels = first.get("labels") instanceof Map<?, ?> l ? l : Map.of();
        Map<?, ?> annotations = first.get("annotations") instanceof Map<?, ?> a ? a : Map.of();

        AlertSeverity severity = AlertSeverity.from(firstNonNull(labels.get("severity"), labels.get("level"), root.get("severity")));
        String service = str(firstNonNull(labels.get("service"), root.get("service")));
        String repo = resolveRepo(root, labels, annotations);
        String branch = str(firstNonNull(labels.get("branch"), root.get("branch"), "main"));
        String title = str(firstNonNull(labels.get("alertname"), annotations.get("summary"), annotations.get("description")));
        String message = str(firstNonNull(annotations.get("summary"), annotations.get("description")));
        String stackTrace = str(firstNonNull(annotations.get("stackTrace"), annotations.get("stack_trace"), annotations.get("description")));
        String fp = resolveFingerprint(root, labels, service, repo, title, message);

        return new NormalizedAlert(severity, service, repo, branch, title, message, stackTrace, fp);
    }

    private NormalizedAlert fromAzureMonitor(Map<String, Object> root, Map<?, ?> data) {
        Map<?, ?> essentials = data.get("essentials") instanceof Map<?, ?> e ? e : Map.of();

        AlertSeverity severity = AlertSeverity.from(firstNonNull(essentials.get("severity"), root.get("severity")));
        String service = str(firstNonNull(essentials.get("targetResourceName"), essentials.get("monitoringService"), root.get("service")));
        String repo = resolveRepo(root, essentials, data);
        String branch = str(firstNonNull(essentials.get("branch"), root.get("branch"), "main"));
        String title = str(firstNonNull(essentials.get("alertRule"), essentials.get("description")));
        String message = str(firstNonNull(essentials.get("description"), essentials.get("alertRule")));

        String stackTrace = null;
        Object alertContext = data.get("alertContext");
        if (alertContext instanceof String s) {
            stackTrace = s;
        } else if (alertContext instanceof Map<?, ?> ctx) {
            stackTrace = str(firstNonNull(ctx.get("stackTrace"), ctx.get("stack_trace"), ctx.get("description"), ctx.toString()));
        }

        String fp = resolveFingerprint(root, essentials, service, repo, title, message);
        return new NormalizedAlert(severity, service, repo, branch, title, message, stackTrace, fp);
    }

    private NormalizedAlert fromCloudWatch(Map<String, Object> root) {
        Object rawSev = root.get("NewStateValue");
        if (rawSev == null) {
            rawSev = root.get("severity");
        }
        AlertSeverity severity = AlertSeverity.from(rawSev);
        String service = str(root.get("service"));
        String repo = resolveRepo(root);
        String branch = str(firstNonNull(root.get("branch"), "main"));
        String title = str(firstNonNull(root.get("AlarmName"), root.get("title")));
        String message = str(firstNonNull(root.get("NewStateReason"), root.get("AlarmDescription"), root.get("message")));
        String stackTrace = str(firstNonNull(root.get("AlarmDescription"), root.get("stackTrace")));
        String fp = resolveFingerprint(root, root, service, repo, title, message);

        return new NormalizedAlert(severity, service, repo, branch, title, message, stackTrace, fp);
    }

    private NormalizedAlert fromDirectJson(Map<String, Object> root) {
        AlertSeverity severity = AlertSeverity.from(firstNonNull(root.get("severity"), root.get("level")));
        String service = str(root.get("service"));
        String repo = resolveRepo(root);
        String branch = str(firstNonNull(root.get("branch"), "main"));
        String title = str(firstNonNull(root.get("title"), root.get("summary"), root.get("message")));
        String message = str(firstNonNull(root.get("message"), root.get("description"), root.get("summary")));
        String stackTrace = str(firstNonNull(root.get("stackTrace"), root.get("stack_trace")));
        String fp = resolveFingerprint(root, root, service, repo, title, message);

        return new NormalizedAlert(severity, service, repo, branch, title, message, stackTrace, fp);
    }

    @SafeVarargs
    private String resolveRepo(Map<?, ?>... scopes) {
        for (Map<?, ?> scope : scopes) {
            if (scope == null) continue;
            for (String key : List.of("repository", "repo", "github_repo", "githubRepo")) {
                Object val = scope.get(key);
                if (val instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            }
        }
        return defaultRepo.filter(r -> !r.isBlank()).orElse(null);
    }

    private String resolveFingerprint(Map<?, ?> root, Map<?, ?> secondary, String service, String repo, String title, String message) {
        if (root != null && root.get("fingerprint") != null) {
            String fp = String.valueOf(root.get("fingerprint")).trim();
            if (!fp.isBlank()) return fp;
        }
        if (secondary != null && secondary.get("fingerprint") != null) {
            String fp = String.valueOf(secondary.get("fingerprint")).trim();
            if (!fp.isBlank()) return fp;
        }
        return hash((service == null ? "" : service) + "|"
                + (repo == null ? "" : repo) + "|"
                + (title == null ? "" : title) + "|"
                + (message == null ? "" : message));
    }

    private static String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }

    private static Object firstNonNull(Object... items) {
        for (Object item : items) {
            if (item != null) return item;
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
