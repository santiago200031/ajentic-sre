package io.ajentic.sre.api.resource;

import io.ajentic.sre.agent.integration.ChatCrypto;
import io.ajentic.sre.agent.llm.ChatMessage;
import io.ajentic.sre.agent.session.AgentSession;
import io.ajentic.sre.agent.session.AgentSessionConfig;
import io.ajentic.sre.agent.session.ChatTurnResult;
import io.ajentic.sre.agent.session.SessionService;
import io.ajentic.sre.agent.session.TurnEvent;
import io.ajentic.sre.agent.session.TurnListener;
import io.ajentic.sre.api.payload.ChatCompletionRequest;
import io.ajentic.sre.api.payload.ChatCompletionResponse;
import io.ajentic.sre.api.payload.ChatMessageDto;
import io.ajentic.sre.api.payload.ModelListResponse;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI-compatible API facade exposing /v1/models and /v1/chat/completions (with SSE stream=true support).
 * Keeps existing /api/v1/... surface authoritative; this facade is a thin adapter without duplicated session logic.
 */
@Path("/v1")
@ApplicationScoped
@Tag(name = "OpenAI Facade", description = "OpenAI-compatible /v1 chat completions and models endpoints")
@RunOnVirtualThread
public class ChatCompletionsResource {

    private static final String DEFAULT_OBJECT_LIST = "list";
    private static final String DEFAULT_OBJECT_MODEL = "model";
    private static final String DEFAULT_OWNED_BY = "ajentic-sre";
    private static final String OBJECT_CHAT_COMPLETION = "chat.completion";
    private static final String OBJECT_CHAT_CHUNK = "chat.completion.chunk";
    private static final String FINISH_STOP = "stop";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_USER = "user";
    private static final long DEFAULT_SEED = 42L;
    private static final int DEFAULT_MAX_ITERATIONS = 8;

    private final SessionService sessions;
    private final ObjectMapper mapper;
    private final String configuredModel;
    private final String defaultScenario;
    private final Optional<String> apiKey;

    @Inject
    public ChatCompletionsResource(
            SessionService sessions,
            ObjectMapper mapper,
            @ConfigProperty(name = "ajentic.llm.model", defaultValue = "Cyber-Tiel-Coder-35B-A3B-UD-Q4_K_XL") String configuredModel,
            @ConfigProperty(name = "ajentic.chat.default-scenario", defaultValue = "orders-bad-deployment") String defaultScenario,
            @ConfigProperty(name = "ajentic.chat.api-key") Optional<String> apiKey) {
        this.sessions = sessions;
        this.mapper = mapper;
        this.configuredModel = configuredModel;
        this.defaultScenario = defaultScenario;
        this.apiKey = apiKey;
    }

    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List models", description = "Lists the configured agent model in OpenAI-compatible format.")
    @APIResponse(responseCode = "200", description = "List of models")
    @APIResponse(responseCode = "401", description = "Unauthorized when api-key is configured")
    public Response listModels(@HeaderParam("Authorization") String authHeader) {
        Response authError = checkAuth(authHeader);
        if (authError != null) {
            return authError;
        }

        ModelListResponse.ModelData modelData = new ModelListResponse.ModelData(
                configuredModel,
                DEFAULT_OBJECT_MODEL,
                Instant.now().getEpochSecond(),
                DEFAULT_OWNED_BY
        );
        return Response.ok(new ModelListResponse(DEFAULT_OBJECT_LIST, List.of(modelData))).build();
    }

    @POST
    @Path("/chat/completions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create chat completion", description = "Creates a model response for the given chat conversation.")
    @APIResponse(responseCode = "200", description = "Chat completion response or SSE stream")
    @APIResponse(responseCode = "401", description = "Unauthorized when api-key is configured")
    @APIResponse(responseCode = "404", description = "Unknown model")
    public Response createChatCompletion(
            ChatCompletionRequest request,
            @HeaderParam("Authorization") String authHeader) {

        Response authError = checkAuth(authHeader);
        if (authError != null) {
            return authError;
        }

        if (request == null) {
            return Response.status(400).entity(Map.of("error", "Request body is required")).build();
        }

        if (request.model() != null && !isAllowedModel(request.model())) {
            return Response.status(404).entity(Map.of(
                    "error", Map.of(
                            "message", "Model '" + request.model() + "' does not exist",
                            "type", "invalid_request_error",
                            "code", "model_not_found"
                    )
            )).build();
        }

        String prompt = extractUserPrompt(request.messages());
        int lastUserIdx = findLastUserIndex(request.messages());
        String activeModel = (request.model() != null && !request.model().isBlank()) ? request.model() : configuredModel;

        boolean isStream = request.isStream();
        if (!isStream) {
            return handleNonStreamCompletion(request.messages(), prompt, lastUserIdx, activeModel, request.user());
        } else {
            return handleStreamCompletion(request.messages(), prompt, lastUserIdx, activeModel, request.user());
        }
    }

    private Response handleNonStreamCompletion(List<ChatMessageDto> messages, String prompt, int lastUserIdx, String modelName, String userId) {
        boolean reuseSession = userId != null && !userId.isBlank();
        boolean[] isNew = new boolean[1];
        AgentSession session = resolveSession(userId, modelName, isNew);
        if (isNew[0]) {
            seedConversation(session, messages, lastUserIdx);
        }
        try {
            ChatTurnResult result = session.chat(prompt);
            long now = Instant.now().getEpochSecond();
            String id = "chatcmpl-" + session.id();

            ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice(
                    0,
                    new ChatMessageDto(ROLE_ASSISTANT, result.answer()),
                    FINISH_STOP
            );

            ChatCompletionResponse.Usage usage = new ChatCompletionResponse.Usage(
                    result.usage().promptTokens(),
                    result.usage().completionTokens(),
                    result.usage().totalTokens()
            );

            ChatCompletionResponse response = new ChatCompletionResponse(
                    id,
                    OBJECT_CHAT_COMPLETION,
                    now,
                    modelName,
                    List.of(choice),
                    usage
            );
            return Response.ok(response, MediaType.APPLICATION_JSON_TYPE).build();
        } finally {
            if (!reuseSession) {
                sessions.delete(session.id());
            }
        }
    }

    private Response handleStreamCompletion(List<ChatMessageDto> messages, String prompt, int lastUserIdx, String modelName, String userId) {
        boolean reuseSession = userId != null && !userId.isBlank();
        boolean[] isNew = new boolean[1];
        AgentSession session = resolveSession(userId, modelName, isNew);
        if (isNew[0]) {
            seedConversation(session, messages, lastUserIdx);
        }
        long created = Instant.now().getEpochSecond();
        String id = "chatcmpl-" + session.id();

        StreamingOutput streamingOutput = (OutputStream outputStream) -> {
            try {
                AtomicBoolean cancelled = new AtomicBoolean(false);
                TurnListener listener = (TurnEvent event) -> {
                    try {
                        if ("thought".equals(event.type())) {
                            Object content = event.payload().get("content");
                            if (content != null && !content.toString().isBlank()) {
                                sendChunk(outputStream, id, created, modelName, content.toString(), null);
                            }
                        } else if ("done".equals(event.type())) {
                            Object answer = event.payload().get("answer");
                            if (answer != null && !answer.toString().isBlank()) {
                                sendChunk(outputStream, id, created, modelName, answer.toString(), null);
                            }
                        }
                    } catch (Exception e) {
                        cancelled.set(true);
                    }
                };

                ChatTurnResult result = session.chat(prompt, listener, cancelled::get);
                // Send final finish chunk
                sendChunk(outputStream, id, created, modelName, null, FINISH_STOP);
                outputStream.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Error during SSE chat completion streaming", e);
            } finally {
                if (!reuseSession) {
                    sessions.delete(session.id());
                }
            }
        };

        return Response.ok(streamingOutput, "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Connection", "keep-alive")
                .build();
    }

    private void sendChunk(OutputStream out, String id, long created, String model, String contentDelta, String finishReason) throws IOException {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", OBJECT_CHAT_CHUNK);
        chunk.put("created", created);
        chunk.put("model", model);

        Map<String, Object> delta = new LinkedHashMap<>();
        if (contentDelta != null) {
            delta.put("role", ROLE_ASSISTANT);
            delta.put("content", contentDelta);
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);

        chunk.put("choices", List.of(choice));
        String json = mapper.writeValueAsString(chunk);
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private AgentSession resolveSession(String userId, String modelName, boolean[] isNew) {
        if (userId != null && !userId.isBlank()) {
            Optional<AgentSession> existing = sessions.find(userId);
            if (existing.isPresent()) {
                isNew[0] = false;
                return existing.get();
            }
        }
        isNew[0] = true;
        AgentSessionConfig config = new AgentSessionConfig(
                defaultScenario,
                DEFAULT_SEED,
                modelName,
                DEFAULT_MAX_ITERATIONS
        );
        return sessions.create(config);
    }

    private static final Set<String> DEFAULT_MODEL_ALIASES = Set.of("default", "ajentic-sre");

    private boolean isAllowedModel(String model) {
        if (model == null) {
            return false;
        }
        String trimmed = model.trim();
        return trimmed.equalsIgnoreCase(configuredModel)
                || DEFAULT_MODEL_ALIASES.contains(trimmed.toLowerCase(Locale.ROOT));
    }

    private int findLastUserIndex(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return -1;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto msg = messages.get(i);
            if (msg.role() != null && ROLE_USER.equalsIgnoreCase(msg.role().trim())) {
                return i;
            }
        }
        return messages.size() - 1;
    }

    private void seedConversation(AgentSession session, List<ChatMessageDto> messages, int lastUserIdx) {
        if (messages == null || lastUserIdx <= 0) {
            return;
        }
        for (int i = 0; i < lastUserIdx; i++) {
            ChatMessageDto msg = messages.get(i);
            if (msg == null || msg.content() == null || msg.content().isBlank()) {
                continue;
            }
            String role = msg.role() != null ? msg.role().toLowerCase(Locale.ROOT).trim() : ROLE_USER;
            switch (role) {
                case ROLE_USER -> session.conversation().add(ChatMessage.user(msg.content()));
                case ROLE_ASSISTANT -> session.conversation().add(ChatMessage.assistant(msg.content()));
                case "system" -> session.conversation().add(ChatMessage.system(msg.content()));
                default -> session.conversation().add(ChatMessage.user(msg.content()));
            }
        }
    }

    private String extractUserPrompt(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        int idx = findLastUserIndex(messages);
        if (idx >= 0 && idx < messages.size()) {
            String content = messages.get(idx).content();
            return content != null ? content : "";
        }
        ChatMessageDto last = messages.getLast();
        return last.content() != null ? last.content() : "";
    }

    private Response checkAuth(String authHeader) {
        if (apiKey.isEmpty() || apiKey.get().isBlank()) {
            return null; // Auth not configured, allow
        }
        if (authHeader == null || authHeader.isBlank()) {
            return unauthorizedResponse();
        }
        String trimmed = authHeader.trim();
        String prefix = "bearer ";
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            return unauthorizedResponse();
        }
        String token = trimmed.substring(prefix.length()).trim();
        if (!ChatCrypto.constantTimeEquals(apiKey.get().trim(), token)) {
            return unauthorizedResponse();
        }
        return null;
    }

    private Response unauthorizedResponse() {
        return Response.status(401).entity(Map.of(
                "error", Map.of(
                        "message", "Incorrect API key provided.",
                        "type", "invalid_request_error",
                        "code", "invalid_api_key"
                )
        )).build();
    }
}
