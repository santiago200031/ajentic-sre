package io.ajentic.sre.agent.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A stateless GitHub REST client built on the JDK {@link HttpClient} and Jackson (zero external dependencies). It reads
 * and writes repository content over HTTP so the agent can inspect a target repo and open a fix without a local git
 * clone. Every non-2xx response is turned into a {@link GitHubException} whose message never echoes the token or the
 * raw response headers.
 */
@ApplicationScoped
public class GitHubClient {

    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$");
    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9._/-]+$");
    private static final int MAX_FILE_BYTES = 200_000;
    private static final int MAX_FILE_LINES = 4_000;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Optional<String> token;
    private final Duration timeout;

    @Inject
    public GitHubClient(
            ObjectMapper mapper,
            @ConfigProperty(name = "ajentic.github.base-url", defaultValue = "https://api.github.com") String baseUrl,
            @ConfigProperty(name = "ajentic.github.token") Optional<String> token,
            @ConfigProperty(name = "ajentic.github.timeout", defaultValue = "30s") Duration timeout) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token.map(String::strip).filter(value -> !value.isBlank());
        this.timeout = timeout;
    }

    public GitHubClient(ObjectMapper mapper, String baseUrl, String token, Duration timeout) {
        this(mapper, baseUrl, Optional.ofNullable(token), timeout);
    }

    /** A decoded file: its text content and the blob sha GitHub returned. */
    public record FileContent(String content, String sha) {
    }

    /** A single GitHub Actions check run. */
    public record CheckRun(String name, String status, String conclusion, String url) {
    }

    /** A parsed check-runs page. */
    public record CiStatus(int totalCount, List<CheckRun> runs) {
    }

    /** A created or existing pull request. */
    public record PullRequest(int number, String url) {
    }

    /** Whether a GitHub token is configured for authenticated (mutating) requests. */
    public boolean hasToken() {
        return token.isPresent();
    }

    /** Lists blob (file) paths under {@code pathPrefix} on {@code ref}; an empty prefix lists the whole tree. */
    public List<String> listFiles(String repo, String ref, String pathPrefix) {
        requireRepo(repo);
        String query = pathPrefix == null || pathPrefix.isBlank() ? "recursive=1"
                : "recursive=1&path_prefix=" + encode(pathPrefix);
        JsonNode root = get("/repos/" + repo + "/git/trees/" + encode(ref) + "?" + query);
        List<String> files = new ArrayList<>();
        JsonNode tree = child(root, "tree");
        if (tree != null) {
            for (JsonNode entry : range(tree)) {
                if ("blob".equals(text(entry, "type"))) {
                    String path = text(entry, "path");
                    if (path == null) {
                        continue;
                    }
                    if (pathPrefix == null || pathPrefix.isBlank() || path.startsWith(pathPrefix)) {
                        files.add(path);
                    }
                }
            }
        }
        return files;
    }

    /** Reads a file's decoded content and blob sha. Directory reads return empty. */
    public Optional<FileContent> readFile(String repo, String path, String ref) {
        requireRepo(repo);
        requirePath(path);
        JsonNode node = get("/repos/" + repo + "/contents/" + encode(path) + "?ref=" + encode(ref));
        if ("dir".equals(text(node, "type"))) {
            return Optional.empty();
        }
        String encoded = text(node, "content");
        if (encoded == null) {
            return Optional.empty();
        }
        String content = new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
        guardSize(content);
        return Optional.of(new FileContent(content, text(node, "sha")));
    }

    /** Creates {@code newBranch} from {@code fromRef}; tolerates a branch that already exists. */
    public void createBranch(String repo, String newBranch, String fromRef) {
        requireRepo(repo);
        requireBranch(newBranch);
        JsonNode ref = get("/repos/" + repo + "/git/ref/heads/" + encode(fromRef));
        String sha = text(ref, "sha");
        try {
            post("/repos/" + repo + "/git/refs",
                    "{\"ref\":\"refs/heads/" + newBranch + "\",\"sha\":\"" + sha + "\"}");
        } catch (GitHubException error) {
            if (error.getMessage() != null && error.getMessage().contains("Reference already exists")) {
                return;
            }
            throw error;
        }
    }

    /** Commits {@code content} at {@code path}; pass {@code existingSha} to update an existing file. Returns the sha. */
    public String commitFile(String repo, String branch, String path, String content, String message,
                             String existingSha) {
        requireRepo(repo);
        requirePath(path);
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder("{\"message\":\"")
                .append(json(message)).append("\",\"content\":\"")
                .append(json(encoded)).append("\",\"branch\":\"")
                .append(json(branch)).append("\"");
        if (existingSha != null && !existingSha.isBlank()) {
            body.append(",\"sha\":\"").append(json(existingSha)).append("\"");
        }
        body.append("}");
        String url = "/repos/" + repo + "/contents/" + encode(path);
        JsonNode root = put(url, body.toString());
        return text(child(root, "commit"), "sha");
    }

    /** Returns the check-run summary for a commit ref. */
    public CiStatus getCiStatus(String repo, String ref) {
        requireRepo(repo);
        JsonNode root = get("/repos/" + repo + "/commits/" + encode(ref) + "/check-runs");
        List<CheckRun> runs = new ArrayList<>();
        JsonNode list = child(root, "check_runs");
        if (list != null) {
            for (JsonNode run : range(list)) {
                runs.add(new CheckRun(text(run, "name"), text(run, "status"), text(run, "conclusion"),
                        text(run, "html_url")));
            }
        }
        return new CiStatus(intChild(root, "total_count"), runs);
    }

    /** Triggers a workflow dispatch event on {@code ref} for {@code workflowId}. */
    public void triggerWorkflow(String repo, String workflowId, String ref) {
        requireRepo(repo);
        String payload = "{\"ref\":\"" + json(ref) + "\"}";
        post("/repos/" + repo + "/actions/workflows/" + encode(workflowId) + "/dispatches", payload);
    }

    /** Opens a pull request, returning an existing open PR for the same head instead of failing on a 422. */
    public PullRequest createPullRequest(String repo, String title, String body, String head, String base) {
        requireRepo(repo);
        String payload = "{\"title\":\"" + json(title)
                + "\",\"body\":\"" + json(body)
                + "\",\"head\":\"" + json(head)
                + "\",\"base\":\"" + json(base) + "\"}";
        try {
            JsonNode root = post("/repos/" + repo + "/pulls", payload);
            return new PullRequest(intChild(root, "number"), text(root, "html_url"));
        } catch (GitHubException error) {
            if (error.getMessage() != null && (error.getMessage().contains("422")
                    || error.getMessage().toLowerCase().contains("already exists"))) {
                PullRequest existing = findOpenPullRequest(repo, head);
                if (existing != null) {
                    return existing;
                }
            }
            throw error;
        }
    }

    private PullRequest findOpenPullRequest(String repo, String head) {
        try {
            JsonNode pulls = get("/repos/" + repo + "/pulls?state=open");
            for (JsonNode pr : range(pulls)) {
                JsonNode headNode = child(pr, "head");
                String ref = headNode != null ? text(headNode, "ref") : null;
                if (head.equals(ref) || (ref != null && (head.endsWith("/" + ref) || head.endsWith(":" + ref)))) {
                    return new PullRequest(intChild(pr, "number"), text(pr, "html_url"));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private JsonNode get(String path) {
        return send(build(RequestBuilder.get(path)).build());
    }

    private JsonNode post(String path, String payload) {
        return send(build(RequestBuilder.post(path).json(payload)).build());
    }

    private JsonNode put(String path, String payload) {
        return send(build(RequestBuilder.put(path).json(payload)).build());
    }

    private HttpRequest.Builder build(RequestBuilder request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + request.path))
                .timeout(timeout)
                .header("User-Agent", "ajentic-sre")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
        token.ifPresent(value -> builder.header("Authorization", "Bearer " + value));
        builder.method(request.method, HttpRequest.BodyPublishers.ofString(request.json));
        return builder;
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception error) {
            throw new GitHubException("GitHub request failed: " + error.getMessage());
        }
        int status = response.statusCode();
        String body = response.body();
        if (status >= 200 && status < 300) {
            return parse(body);
        }
        throw new GitHubException(githubMessage(status, body));
    }

    private JsonNode parse(String body) {
        try {
            return body.isBlank() ? null : mapper.readTree(body);
        } catch (Exception error) {
            throw new GitHubException("Unexpected GitHub response: " + error.getMessage());
        }
    }

    private String githubMessage(int status, String body) {
        JsonNode root = parse(body);
        String message = root != null && root.has("message") ? text(root, "message") : null;
        if (message == null) {
            return "GitHub returned status " + status + ".";
        }
        return "GitHub returned status " + status + ": " + message;
    }

    private static void requireRepo(String repo) {
        if (repo == null || !REPO.matcher(repo).matches()) {
            throw new GitHubException("Invalid repository, expected owner/name: " + repo);
        }
    }

    private static void requirePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || !PATH.matcher(path).matches()
                || path.contains("..")) {
            throw new GitHubException("Invalid file path: " + path);
        }
    }

    private static void requireBranch(String branch) {
        if (branch == null || !branch.startsWith("ajentic/")) {
            throw new GitHubException("Branch must be prefixed with ajentic/: " + branch);
        }
    }

    private static void guardSize(String content) {
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES
                || content.lines().count() > MAX_FILE_LINES) {
            throw new GitHubException("File exceeds read limits (" + MAX_FILE_BYTES + " bytes, "
                    + MAX_FILE_LINES + " lines).");
        }
    }

    private static String encode(String value) {
        // Preserve '/' so file paths match the GitHub contents API and test stubs without %2F encoding.
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%2F", "/");
    }

    /** One-level nullable string child, returning {@code null} when absent or null. */
    private static String text(JsonNode root, String field) {
        if (root == null || !root.has(field) || root.get(field).isNull()) {
            return null;
        }
        return root.get(field).asText();
    }

    /** One-level integer child, returning {@code def} when absent or null. */
    private static int intChild(JsonNode root, String field) {
        return root == null || !root.has(field) || root.get(field).isNull() ? 0 : root.get(field).asInt();
    }

    /** One-level object child, returning {@code null} when absent or null. */
    private static JsonNode child(JsonNode root, String field) {
        return (root == null || !root.has(field) || root.get(field).isNull()) ? null : root.get(field);
    }

    private static List<JsonNode> range(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (int index = 0; index < array.size(); index++) {
                items.add(array.get(index));
            }
        }
        return items;
    }

    /** JSON-escapes a string for embedding inside a JSON string literal. */
    private static String json(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }

    private record RequestBuilder(String method, String path, String json) {
        static RequestBuilder get(String path) {
            return new RequestBuilder("GET", path, "");
        }

        static RequestBuilder post(String path) {
            return new RequestBuilder("POST", path, "");
        }

        static RequestBuilder put(String path) {
            return new RequestBuilder("PUT", path, "");
        }

        RequestBuilder json(String payload) {
            return new RequestBuilder(method, path, payload);
        }
    }
}
