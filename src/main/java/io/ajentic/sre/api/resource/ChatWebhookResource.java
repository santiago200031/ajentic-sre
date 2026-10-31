package io.ajentic.sre.api.resource;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.ajentic.sre.agent.approval.ApprovalOutcome;
import io.ajentic.sre.agent.github.CiFeedbackLoop;
import io.ajentic.sre.agent.github.CiStatusKind;
import io.ajentic.sre.agent.github.GitHubClient;
import io.ajentic.sre.agent.tool.builtin.GitHubCodeTools;
import io.ajentic.sre.agent.github.RemediationRecord;
import io.ajentic.sre.agent.github.RemediationStore;
import io.ajentic.sre.agent.llm.ChatMessage;
import io.ajentic.sre.agent.llm.ChatRole;
import io.ajentic.sre.agent.llm.ToolCall;
import io.ajentic.sre.agent.mcp.SreAgentPrompts;
import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.session.ChatTurnResult;
import io.ajentic.sre.agent.session.SessionService;
import io.ajentic.sre.agent.integration.ChatCrypto;
import io.ajentic.sre.api.payload.ChatWebhookResponse;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inbound ChatOps webhook endpoint accepting commands from Slack, Teams, Google Chat, Discord, Telegram, and generic clients.
 * Verifies signatures, deduplicates by provider event id, and executes agent commands.
 */
@Path("/api/v1/webhooks/chat")
@ApplicationScoped
@Tag(name = "ChatOps", description = "Inbound ChatOps command intake and event processing")
@RunOnVirtualThread
public class ChatWebhookResource {

    private static final Logger LOG = Logger.getLogger(ChatWebhookResource.class);
    private static final Pattern PR_URL_PATTERN = Pattern.compile("https://github\\.com/([^/]+/[^/]+)/pull/(\\d+)");
    private static final Pattern BRANCH_PATTERN = Pattern.compile("ajentic/fix-[0-9a-fA-F-]+");
    private static final long DEFAULT_SEED = 42L;
    private static final int DEFAULT_MAX_ITERATIONS = 8;

    private final SessionService sessions;
    private final io.ajentic.sre.rl.episode.EpisodeService episodeService;
    private final RemediationStore remediationStore;
    private final GitHubClient github;
    private final ObjectMapper mapper;
    private final String defaultScenario;
    private final Optional<String> model;
    private final Optional<String> slackSigningSecret;
    private final Optional<String> twilioAuthToken;
    private final Optional<String> googleJwtSecret;

    private final Cache<String, String> deduplicationCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    @Inject
    public ChatWebhookResource(
            SessionService sessions,
            io.ajentic.sre.rl.episode.EpisodeService episodeService,
            RemediationStore remediationStore,
            GitHubClient github,
            ObjectMapper mapper,
            @ConfigProperty(name = "ajentic.chat.default-scenario", defaultValue = "orders-bad-deployment") String defaultScenario,
            @ConfigProperty(name = "ajentic.alerts.model") Optional<String> model,
            @ConfigProperty(name = "ajentic.chat.slack.signing-secret") Optional<String> slackSigningSecret,
            @ConfigProperty(name = "ajentic.chat.twilio.auth-token") Optional<String> twilioAuthToken,
            @ConfigProperty(name = "ajentic.chat.google.jwt-secret") Optional<String> googleJwtSecret) {
        this.sessions = sessions;
        this.episodeService = episodeService;
        this.remediationStore = remediationStore;
        this.github = github;
        this.mapper = mapper;
        this.defaultScenario = defaultScenario;
        this.model = model;
        this.slackSigningSecret = slackSigningSecret;
        this.twilioAuthToken = twilioAuthToken;
        this.googleJwtSecret = googleJwtSecret;
    }

    @POST
    @Path("/{provider}")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_FORM_URLENCODED, MediaType.TEXT_PLAIN, "*/*"})
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Intake inbound chat command",
            description = "Accepts Slack slash command, Teams activity, Google Chat event, or Discord/Telegram updates.")
    @APIResponse(responseCode = "200", description = "Command executed successfully")
    @APIResponse(responseCode = "401", description = "Invalid or expired provider signature")
    public Response receiveCommand(
            @PathParam("provider") String provider,
            String rawBody,
            @HeaderParam("X-Slack-Signature") String slackSignature,
            @HeaderParam("X-Slack-Request-Timestamp") String slackTimestamp,
            @HeaderParam("X-Twilio-Signature") String twilioSignature,
            @HeaderParam("Authorization") String authHeader,
            @HeaderParam("X-Slack-Request-Id") String slackRequestId,
            @HeaderParam("X-Event-Id") String headerEventId) {

        String prov = provider != null ? provider.toLowerCase(Locale.ROOT) : "generic";

        // 1. Verify signatures
        Response authError = verifySignature(prov, rawBody, slackSignature, slackTimestamp, twilioSignature, authHeader);
        if (authError != null) {
            return authError;
        }

        // 2. Parse payload and extract command & event id
        ParsedChatPayload parsed = parsePayload(prov, rawBody, slackRequestId, headerEventId);
        String commandText = parsed.commandText();
        String eventId = parsed.eventId();

        if (commandText == null || commandText.isBlank()) {
            return buildResponse(prov, "No command provided.");
        }

        // 3. Atomic deduplication & command execution
        String answer;
        if (eventId != null && !eventId.isBlank()) {
            String dedupKey = prov + ":" + eventId;
            try {
                answer = deduplicationCache.get(dedupKey, k -> executeCommand(commandText));
            } catch (Exception e) {
                LOG.errorf("Failed executing chat command: %s", e.getMessage());
                return buildResponse(prov, "Error executing command: " + e.getMessage());
            }
        } else {
            answer = executeCommand(commandText);
        }

        return buildResponse(prov, answer);
    }

    private Response verifySignature(
            String provider,
            String rawBody,
            String slackSignature,
            String slackTimestamp,
            String twilioSignature,
            String authHeader) {

        if (provider.equals("slack")) {
            if (slackSigningSecret.isPresent() && !slackSigningSecret.get().isBlank()) {
                if (slackSignature == null || slackTimestamp == null) {
                    return Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Missing Slack signature or timestamp"))
                            .build();
                }
                long ts;
                try {
                    ts = Long.parseLong(slackTimestamp);
                } catch (NumberFormatException e) {
                    return Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Invalid Slack timestamp"))
                            .build();
                }
                long now = System.currentTimeMillis() / 1000;
                if (Math.abs(now - ts) > 300) {
                    return Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Expired Slack request timestamp"))
                            .build();
                }
                String body = rawBody != null ? rawBody : "";
                String baseString = "v0:" + ts + ":" + body;
                String expectedSig = "v0=" + ChatCrypto.hmacSha256Hex(baseString, slackSigningSecret.get().trim());
                if (!ChatCrypto.constantTimeEquals(expectedSig, slackSignature)) {
                    return Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Invalid Slack signature"))
                            .build();
                }
            }
        } else if (provider.equals("twilio")) {
            if (twilioAuthToken.isPresent() && !twilioAuthToken.get().isBlank()) {
                if (twilioSignature == null) {
                    return Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Missing Twilio signature"))
                            .build();
                }
                String body = rawBody != null ? rawBody : "";
                String expectedSig = ChatCrypto.hmacSha1Base64(body, twilioAuthToken.get().trim());
                if (!ChatCrypto.constantTimeEquals(expectedSig, twilioSignature)) {
                    return Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Invalid Twilio signature"))
                            .build();
                }
            }
        } else if (provider.equals("google-chat") || provider.equals("google")) {
            if (googleJwtSecret.isPresent() && !googleJwtSecret.get().isBlank()) {
                if (authHeader == null || !authHeader.regionMatches(true, 0, "bearer ", 0, 7)) {
                    return Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Missing Google JWT bearer token"))
                            .build();
                }
                String token = authHeader.substring(7).trim();
                if (!verifyGoogleJwt(token, googleJwtSecret.get().trim())) {
                    return Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Invalid or expired Google JWT token"))
                            .build();
                }
            }
        }

        return null;
    }

    private boolean verifyGoogleJwt(String token, String secret) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        try {
            String unsigned = parts[0] + "." + parts[1];
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signatureBytes = mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
            String actualSig = parts[2].replace("=", "");
            if (!ChatCrypto.constantTimeEquals(expected, actualSig)) {
                return false;
            }
            // Check exp claim in payload if present
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = mapper.readTree(payloadBytes);
            if (payload.has("exp")) {
                long exp = payload.get("exp").asLong();
                if (exp < Instant.now().getEpochSecond()) {
                    return false; // Expired
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ParsedChatPayload parsePayload(String provider, String rawBody, String slackRequestId, String headerEventId) {
        String eventId = headerEventId;
        String text = null;

        if (rawBody == null || rawBody.isBlank()) {
            return new ParsedChatPayload(eventId, "");
        }

        String trimmed = rawBody.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode root = mapper.readTree(trimmed);
                if (root.has("event_id")) {
                    eventId = root.path("event_id").asText();
                } else if (root.has("id")) {
                    eventId = root.path("id").asText();
                } else if (root.has("update_id")) {
                    eventId = root.path("update_id").asText();
                } else if (root.has("eventId")) {
                    eventId = root.path("eventId").asText();
                }

                if (root.has("event")) {
                    text = root.path("event").path("text").asText(null);
                } else if (root.has("message") && root.path("message").isObject()) {
                    text = root.path("message").path("text").asText(null);
                    if (eventId == null) {
                        eventId = root.path("message").path("name").asText(root.path("eventTime").asText(null));
                    }
                } else if (root.has("data")) {
                    JsonNode data = root.path("data");
                    String cmdName = data.path("name").asText(null);
                    String val = null;
                    if (data.has("options") && data.path("options").isArray() && !data.path("options").isEmpty()) {
                        val = data.path("options").path(0).path("value").asText(null);
                    }
                    if (cmdName != null && !cmdName.isBlank()) {
                        text = (val != null && !val.isBlank()) ? cmdName + " " + val : cmdName;
                    } else if (val != null) {
                        text = val;
                    }
                }

                if (text == null && root.has("text")) {
                    text = root.path("text").asText();
                } else if (text == null && root.has("content")) {
                    text = root.path("content").asText();
                } else if (text == null && root.has("command")) {
                    text = root.path("command").asText();
                }
            } catch (Exception e) {
                LOG.warnf("Failed to parse JSON body: %s", e.getMessage());
            }
        }

        if (text == null) {
            // Form-urlencoded fallback
            Map<String, String> form = parseForm(rawBody);
            String command = form.get("command");
            String formText = form.get("text");
            if (eventId == null) {
                eventId = form.getOrDefault("trigger_id", form.get("command_id"));
            }

            if (command != null && !command.isBlank()) {
                String cmd = command.startsWith("/") ? command.substring(1) : command;
                if (!cmd.equalsIgnoreCase("ajentic") && formText != null) {
                    text = cmd + " " + formText;
                } else if (formText != null) {
                    text = formText;
                } else {
                    text = cmd;
                }
            } else if (formText != null) {
                text = formText;
            } else {
                text = rawBody;
            }
        }

        if (eventId == null && slackRequestId != null) {
            eventId = slackRequestId;
        }

        return new ParsedChatPayload(eventId, text != null ? text.trim() : "");
    }

    private Map<String, String> parseForm(String raw) {
        Map<String, String> map = new HashMap<>();
        String[] pairs = raw.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                try {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                    map.put(key, val);
                } catch (Exception ignored) {
                }
            }
        }
        return map;
    }

    private String executeCommand(String input) {
        String clean = input.trim();
        if (clean.startsWith("/")) {
            clean = clean.substring(1).trim();
        }
        if (clean.startsWith("@")) {
            int spaceIdx = clean.indexOf(' ');
            if (spaceIdx > 0) {
                clean = clean.substring(spaceIdx + 1).trim();
            }
        } else if (clean.startsWith("<users/")) {
            int closeIdx = clean.indexOf('>');
            if (closeIdx > 0) {
                clean = clean.substring(closeIdx + 1).trim();
            }
        }

        int space = clean.indexOf(' ');
        String verb = space > 0 ? clean.substring(0, space).toLowerCase(Locale.ROOT) : clean.toLowerCase(Locale.ROOT);
        int atIdx = verb.indexOf('@');
        if (atIdx > 0) {
            verb = verb.substring(0, atIdx);
        }
        String arg = space > 0 ? clean.substring(space + 1).trim() : "";

        return switch (verb) {
            case "ask" -> handleAsk(arg);
            case "status" -> handleStatus(arg);
            case "fix" -> handleFix(arg);
            case "approve" -> handleApprovalDecision(arg, true);
            case "deny" -> handleApprovalDecision(arg, false);
            default -> handleAsk(clean);
        };
    }

    private String handleAsk(String question) {
        if (question == null || question.isBlank()) {
            return "Usage: ask <question>";
        }
        AgentSessionConfig config = new AgentSessionConfig(
                defaultScenario,
                DEFAULT_SEED,
                model.orElse(null),
                DEFAULT_MAX_ITERATIONS);
        AgentSession session = sessions.create(config);
        try {
            ChatTurnResult result = session.chat(question);
            return result.answer();
        } finally {
            sessions.delete(session.id());
        }
    }

    private String handleStatus(String target) {
        if (target == null || target.isBlank()) {
            return "Usage: status <episode|repo>";
        }

        // 1. Search session by ID
        Optional<AgentSession> sessionOpt = sessions.find(target);
        if (sessionOpt.isPresent()) {
            AgentSession session = sessionOpt.get();
            boolean resolved = session.environment().observe().resolved();
            return String.format("Session %s: scenario=%s, status=%s, pendingApprovals=%d",
                    session.id(),
                    session.config().scenarioId(),
                    resolved ? "RESOLVED" : "IN_PROGRESS",
                    session.pendingApprovals().size());
        }

        // 2. Search episode by ID in EpisodeService
        Optional<io.ajentic.sre.rl.episode.EpisodeHandle> episodeOpt = episodeService.find(target);
        if (episodeOpt.isPresent()) {
            io.ajentic.sre.rl.episode.EpisodeHandle ep = episodeOpt.get();
            boolean resolved = ep.environment().observe().resolved();
            return String.format("Episode %s: status=%s, terminal=%s",
                    ep.id(),
                    resolved ? "RESOLVED" : "IN_PROGRESS",
                    ep.environment().isTerminal());
        }

        // 3. Search remediation by repo or ID
        Optional<RemediationRecord> recOpt = remediationStore.findLatestForRepo(target);
        if (recOpt.isEmpty()) {
            recOpt = remediationStore.findById(target);
        }
        if (recOpt.isPresent()) {
            RemediationRecord rec = recOpt.get();
            return String.format("Remediation for %s: branch=%s, pr=#%d (%s), ciStatus=%s, commit=%s",
                    rec.repository(),
                    rec.branch(),
                    rec.prNumber(),
                    rec.prUrl(),
                    rec.ciStatus(),
                    rec.commitSha());
        }

        return "No active session or remediation record found for '" + target + "'.";
    }

    private String handleFix(String arg) {
        if (arg == null || arg.isBlank()) {
            return "Usage: fix <repo> <trace>";
        }
        int space = arg.indexOf(' ');
        String repo = space > 0 ? arg.substring(0, space).trim() : arg;
        String trace = space > 0 ? arg.substring(space + 1).trim() : "Automated fix requested";

        String prompt = SreAgentPrompts.buildCodeRemediationPrompt(
                repo,
                "main",
                "ChatOps Fix: " + repo,
                "Fix requested via chatops",
                trace,
                UUID.randomUUID().toString());

        AgentSessionConfig config = new AgentSessionConfig(
                defaultScenario,
                DEFAULT_SEED,
                model.orElse(null),
                DEFAULT_MAX_ITERATIONS);

        AgentSession session = sessions.create(config);
        try {
            ChatTurnResult result = session.chat(prompt);
            RemediationRecord remediation = extractRemediation(repo, session, result);
            if (remediation != null) {
                remediationStore.record(remediation);
            }
            return result.answer();
        } finally {
            sessions.delete(session.id());
        }
    }

    private RemediationRecord extractRemediation(String repo, AgentSession session, ChatTurnResult result) {
        String branch = null;
        String commitSha = null;
        int prNumber = 0;
        String prUrl = null;

        for (ChatMessage msg : session.conversation().messages()) {
            if (msg.toolCalls() != null) {
                for (ToolCall tc : msg.toolCalls()) {
                    if (GitHubCodeTools.TOOL_GITHUB_CREATE_BRANCH.equals(tc.name())) {
                        Object b = tc.arguments().get(GitHubCodeTools.PARAM_NEW_BRANCH);
                        if (b != null) branch = b.toString();
                    } else if (GitHubCodeTools.TOOL_GITHUB_COMMIT_FILE.equals(tc.name())) {
                        Object b = tc.arguments().get(GitHubCodeTools.PARAM_BRANCH);
                        if (b != null && branch == null) branch = b.toString();
                    } else if (GitHubCodeTools.TOOL_GITHUB_GET_CI_STATUS.equals(tc.name())) {
                        Object r = tc.arguments().get(GitHubCodeTools.PARAM_REF);
                        if (r != null && commitSha == null) commitSha = r.toString();
                    } else if (GitHubCodeTools.TOOL_GITHUB_CREATE_PULL_REQUEST.equals(tc.name())) {
                        Object h = tc.arguments().get(GitHubCodeTools.PARAM_HEAD);
                        if (h != null && branch == null) branch = h.toString();
                    }
                }
            }
            if (msg.role() == ChatRole.TOOL && msg.content() != null) {
                if (commitSha == null && msg.content().contains("(") && msg.content().contains(")")) {
                    int start = msg.content().lastIndexOf('(');
                    int end = msg.content().lastIndexOf(')');
                    if (start >= 0 && end > start) {
                        commitSha = msg.content().substring(start + 1, end).trim();
                    }
                }
                Matcher matcher = PR_URL_PATTERN.matcher(msg.content());
                if (matcher.find()) {
                    prUrl = matcher.group(0);
                    prNumber = Integer.parseInt(matcher.group(2));
                }
            }
        }

        if (prUrl == null && result.answer() != null) {
            Matcher matcher = PR_URL_PATTERN.matcher(result.answer());
            if (matcher.find()) {
                prUrl = matcher.group(0);
                prNumber = Integer.parseInt(matcher.group(2));
            }
        }

        if (prUrl == null) {
            return null;
        }

        if (branch == null && result.answer() != null) {
            Matcher m = BRANCH_PATTERN.matcher(result.answer());
            if (m.find()) {
                branch = m.group(0);
            }
        }

        String ciStatus = CiStatusKind.PENDING.value();
        try {
            String ref = commitSha != null ? commitSha : branch;
            if (ref != null) {
                ciStatus = CiFeedbackLoop.computeStatus(github.getCiStatus(repo, ref));
            }
        } catch (Exception ignored) {
        }

        return new RemediationRecord(
                UUID.randomUUID().toString(),
                repo,
                branch != null ? branch : "main",
                commitSha,
                prNumber,
                prUrl,
                ciStatus,
                Instant.now(),
                0);
    }

    private String handleApprovalDecision(String approvalId, boolean isApproved) {
        if (approvalId == null || approvalId.isBlank()) {
            return isApproved ? "Usage: approve <id>" : "Usage: deny <id>";
        }

        String cleanId = approvalId.trim();
        for (AgentSession session : sessions.activeSessions()) {
            ApprovalOutcome outcome = session.decideApproval(cleanId, isApproved, isApproved ? "Approved via ChatOps" : "Denied via ChatOps");
            if (outcome != null) {
                if (isApproved) {
                    return "Approval " + cleanId + " approved: " + (outcome.executed() ? "executed successfully." : "not executed.");
                } else {
                    return "Approval " + cleanId + " denied.";
                }
            }
        }

        return "Unknown approval id: " + cleanId;
    }

    private Response buildResponse(String provider, String text) {
        return switch (provider) {
            case "slack" -> Response.ok(ChatWebhookResponse.inChannel(text)).build();
            case "teams" -> Response.ok(Map.of("type", "message", "text", text)).build();
            case "google-chat", "google" -> Response.ok(Map.of("text", text)).build();
            case "telegram" -> Response.ok(Map.of("method", "sendMessage", "text", text)).build();
            case "discord" -> Response.ok(Map.of("type", 4, "data", Map.of("content", text))).build();
            default -> Response.ok(Map.of("status", "ok", "text", text)).build();
        };
    }

    private record ParsedChatPayload(String eventId, String commandText) {
    }
}
