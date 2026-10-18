package io.ajentic.sre.agent.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ajentic.sre.agent.tool.Tool;
import io.ajentic.sre.agent.tool.ToolAuthorization;
import io.ajentic.sre.agent.tool.ToolContext;
import io.ajentic.sre.agent.tool.ToolDescriptor;
import io.ajentic.sre.agent.tool.ToolInvocation;
import io.ajentic.sre.agent.tool.ToolRegistry;
import io.ajentic.sre.agent.tool.ToolResult;
import io.ajentic.sre.rl.episode.EpisodeCursor;
import io.ajentic.sre.rl.episode.EpisodeHandle;
import io.ajentic.sre.rl.episode.EpisodeService;
import io.ajentic.sre.rl.episode.UnknownEpisodeException;
import io.ajentic.sre.rl.simulation.Runbook;
import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ScenarioRepository;
import io.ajentic.sre.rl.simulation.ServiceSpec;
import io.ajentic.sre.rl.simulation.UnknownScenarioException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Model Context Protocol (JSON-RPC 2.0) handler. It is intentionally transport agnostic: {@link #handle(String)}
 * powers the stdio CLI while the HTTP resource feeds it JSON request bodies. Tools come from the shared
 * {@link ToolRegistry} and prompts from the {@link PromptCatalog}, so both transports see the same capabilities.
 */
@ApplicationScoped
public class McpProtocolHandler {

    public static final String PROTOCOL_VERSION = "2024-11-05";

    private static final String SERVER_VERSION = "1.0.0";
    public static final String SERVER_NAME = "ajentic-sre";
    private static final String SCENARIO_URI_PREFIX = "sre://scenarios/";
    private static final String RUNBOOK_URI_PREFIX = "sre://runbooks/";
    private static final long DEFAULT_SEED = 42L;
    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() {
    };

    private static final String LIST_SCENARIOS = "list_scenarios";
    private static final String START_EPISODE = "start_episode";
    private static final String RUN_INCIDENT_DRILL = "run_incident_drill";
    private static final Set<String> LIFECYCLE_TOOLS = Set.of(LIST_SCENARIOS, START_EPISODE, RUN_INCIDENT_DRILL);

    private final ObjectMapper mapper;
    private final ToolRegistry tools;
    private final PromptCatalog prompts;
    private final EpisodeService episodes;
    private final EpisodeCursor cursor;
    private final ScenarioRepository scenarios;
    private final ToolAuthorization authorization;
    private final String defaultScenarioId;

    @Inject
    public McpProtocolHandler(
            ObjectMapper mapper,
            ToolRegistry tools,
            PromptCatalog prompts,
            EpisodeService episodes,
            EpisodeCursor cursor,
            ScenarioRepository scenarios,
            ToolAuthorization authorization,
            @ConfigProperty(name = "ajentic.mcp.default-scenario", defaultValue = "checkout-latency-db-pool")
            String defaultScenarioId) {
        this.mapper = mapper;
        this.tools = tools;
        this.prompts = prompts;
        this.episodes = episodes;
        this.cursor = cursor;
        this.scenarios = scenarios;
        this.authorization = authorization;
        this.defaultScenarioId = defaultScenarioId;
    }

    /**
     * Handles one JSON document, which may be a single request, a batch array, or malformed JSON. Returns {@code null}
     * when there is nothing to reply to (a lone notification).
     */
    public String handle(String json) {
        JsonNode request;
        try {
            request = mapper.readTree(json);
        } catch (JacksonException exception) {
            return write(JsonRpcResponse.error(mapper.nullNode(), JsonRpcErrorCode.PARSE_ERROR, exception.getMessage()));
        }
        if (request == null || request.isNull()) {
            return write(JsonRpcResponse.error(
                    mapper.nullNode(), JsonRpcErrorCode.INVALID_REQUEST, "Empty request"));
        }
        if (request.isArray()) {
            return handleBatch((ArrayNode) request);
        }
        JsonNode response = handle(request);
        return response == null ? null : mapper.writeValueAsString(response);
    }

    public JsonNode handle(JsonNode request) {
        if (!request.hasNonNull("id")) {
            return null;
        }
        JsonNode id = request.get("id");
        String methodName = request.path("method").asText("");
        Optional<McpMethod> method = McpMethod.fromWireName(methodName);
        if (method.isEmpty()) {
            return wrap(JsonRpcResponse.error(id, JsonRpcErrorCode.METHOD_NOT_FOUND, "Method not found: " + methodName));
        }
        try {
            return wrap(JsonRpcResponse.result(id, dispatch(method.get(), request.path("params"))));
        } catch (IllegalArgumentException exception) {
            return wrap(JsonRpcResponse.error(id, JsonRpcErrorCode.INVALID_PARAMS, message(exception)));
        } catch (RuntimeException exception) {
            return wrap(JsonRpcResponse.error(id, JsonRpcErrorCode.INTERNAL_ERROR, message(exception)));
        }
    }

    /** Clears the active default episode, used by the HTTP {@code DELETE} transport hook. */
    public void reset() {
        cursor.clear().ifPresent(episodes::delete);
    }

    private Object dispatch(McpMethod method, JsonNode params) {
        return switch (method) {
            case INITIALIZE -> initialize();
            case PING -> Map.of();
            case TOOLS_LIST -> listTools();
            case TOOLS_CALL -> callTool(params);
            case RESOURCES_LIST -> listResources();
            case RESOURCES_READ -> readResource(params);
            case PROMPTS_LIST -> listPrompts();
            case PROMPTS_GET -> getPrompt(params);
        };
    }

    private String handleBatch(ArrayNode batch) {
        ArrayNode responses = mapper.createArrayNode();
        for (JsonNode element : batch) {
            JsonNode response = handle(element);
            if (response != null) {
                responses.add(response);
            }
        }
        return responses.isEmpty() ? null : mapper.writeValueAsString(responses);
    }

    private InitializeResult initialize() {
        return new InitializeResult(
                PROTOCOL_VERSION,
                new Capabilities(
                        new ToolCapability(false),
                        new ResourceCapability(false, false),
                        new PromptCapability(false)),
                new ServerInfo(SERVER_NAME, SERVER_VERSION));
    }

    private ToolsListResult listTools() {
        List<McpToolView> views = tools.descriptors().stream()
                .map(descriptor -> new McpToolView(descriptor.name(), descriptor.description(), descriptor.parameters()))
                .toList();
        return new ToolsListResult(views);
    }

    private ToolCallResult callTool(JsonNode params) {
        String name = params.path("name").asText("");
        Map<String, Object> arguments = arguments(params.path("arguments"));

        Optional<Tool> tool = tools.find(name);
        if (tool.isEmpty()) {
            return toolError("Unknown tool: " + name);
        }
        if (!authorization.isAuthorized(tool.get().descriptor())) {
            return toolError("Tool '%s' requires the %s capability and is not authorized."
                    .formatted(name, tool.get().descriptor().capability()));
        }

        ToolContext context = null;
        if (!LIFECYCLE_TOOLS.contains(name)) {
            String scenarioId = stringArgument(arguments, "_scenarioId");
            String episodeId = stringArgument(arguments, "_episodeId");
            EpisodeHandle handle;
            try {
                handle = resolveEpisode(episodeId, scenarioId);
            } catch (UnknownEpisodeException | UnknownScenarioException exception) {
                return toolError(exception.getMessage());
            }
            context = new ToolContext(handle.id(), handle.environment());
        }

        try {
            ToolResult result = tool.get().invoke(new ToolInvocation(name, arguments), context);
            return result.success() ? toolResult(result) : toolError(result.content());
        } catch (RuntimeException exception) {
            return toolError(message(exception));
        }
    }

    private ResourcesListResult listResources() {
        List<McpResourceView> resources = new java.util.ArrayList<>();
        for (Scenario scenario : scenarios.all()) {
            resources.add(new McpResourceView(
                    SCENARIO_URI_PREFIX + scenario.id(),
                    scenario.title(),
                    scenario.summary(),
                    "application/json"));
            for (Runbook runbook : scenario.runbooks()) {
                resources.add(new McpResourceView(
                        RUNBOOK_URI_PREFIX + scenario.id() + "/" + runbook.id(),
                        runbook.title(),
                        null,
                        "text/plain"));
            }
        }
        return new ResourcesListResult(resources);
    }

    private ResourceReadResult readResource(JsonNode params) {
        String uri = params.path("uri").asText("");
        if (uri.startsWith(SCENARIO_URI_PREFIX)) {
            String scenarioId = uri.substring(SCENARIO_URI_PREFIX.length());
            Scenario scenario = scenarios.byId(scenarioId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown scenario resource: " + uri));
            return new ResourceReadResult(List.of(new ResourceContent(
                    uri, "application/json", mapper.writeValueAsString(ScenarioPayload.from(scenario)))));
        }
        if (uri.startsWith(RUNBOOK_URI_PREFIX)) {
            String remainder = uri.substring(RUNBOOK_URI_PREFIX.length());
            String[] parts = remainder.split("/", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Malformed runbook resource uri: " + uri);
            }
            Scenario scenario = scenarios.byId(parts[0])
                    .orElseThrow(() -> new IllegalArgumentException("Unknown scenario resource: " + uri));
            Runbook runbook = scenario.runbooks().stream()
                    .filter(candidate -> candidate.id().equals(parts[1]))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown runbook resource: " + uri));
            return new ResourceReadResult(List.of(new ResourceContent(
                    uri, "text/plain", runbook.title() + "\n\n" + runbook.body())));
        }
        throw new IllegalArgumentException("Unknown resource uri: " + uri);
    }

    private PromptsListResult listPrompts() {
        List<McpPromptView> views = prompts.all().stream()
                .map(view -> new McpPromptView(
                        view.name(),
                        view.description(),
                        view.arguments().stream()
                                .map(argument -> new PromptArgument(
                                        argument.name(), argument.description(), argument.required()))
                                .toList()))
                .toList();
        return new PromptsListResult(views);
    }

    private PromptGetResult getPrompt(JsonNode params) {
        String name = params.path("name").asText("");
        Map<String, Object> arguments = arguments(params.path("arguments"));
        String text = prompts.render(name, arguments);
        String description = prompts.find(name).map(PromptCatalog.PromptView::description).orElse(name);
        return new PromptGetResult(
                description,
                List.of(new PromptMessage("user", new PromptContent("text", text))));
    }

    private EpisodeHandle resolveEpisode(String episodeId, String scenarioId) {
        if (episodeId != null) {
            return episodes.find(episodeId).orElseThrow(() -> new UnknownEpisodeException(episodeId));
        }
        String activeId = cursor.activeId();
        if (activeId != null && scenarioId == null) {
            Optional<EpisodeHandle> active = episodes.find(activeId);
            if (active.isPresent()) {
                return active.get();
            }
        }
        String target = scenarioId == null ? defaultScenarioId : scenarioId;
        EpisodeHandle handle = episodes.start(target, DEFAULT_SEED);
        cursor.activate(handle.id());
        return handle;
    }

    private ToolCallResult toolResult(ToolResult result) {
        return new ToolCallResult(
                List.of(new ContentBlock("text", result.content())),
                result.data().isEmpty() ? null : result.data(),
                false);
    }

    private ToolCallResult toolError(String message) {
        return new ToolCallResult(List.of(new ContentBlock("text", message)), null, true);
    }

    private JsonNode wrap(JsonRpcResponse response) {
        return mapper.valueToTree(response);
    }

    private String write(JsonRpcResponse response) {
        return mapper.writeValueAsString(response);
    }

    private Map<String, Object> arguments(JsonNode argumentsNode) {
        if (argumentsNode.isMissingNode() || argumentsNode.isNull()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(mapper.convertValue(argumentsNode, ARGUMENTS));
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static String stringArgument(Map<String, Object> arguments, String key) {
        Object value = arguments.remove(key);
        return value == null ? null : String.valueOf(value);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record JsonRpcResponse(String jsonrpc, JsonNode id, Object result, JsonRpcError error) {

        static JsonRpcResponse result(JsonNode id, Object result) {
            return new JsonRpcResponse("2.0", id, result, null);
        }

        static JsonRpcResponse error(JsonNode id, JsonRpcErrorCode code, String message) {
            return new JsonRpcResponse(
                    "2.0",
                    id,
                    null,
                    new JsonRpcError(
                            code.code(),
                            message == null || message.isBlank() ? code.defaultMessage() : message));
        }
    }

    record JsonRpcError(int code, String message) {
    }

    record InitializeResult(String protocolVersion, Capabilities capabilities, ServerInfo serverInfo) {
    }

    record Capabilities(ToolCapability tools, ResourceCapability resources, PromptCapability prompts) {
    }

    record ToolCapability(boolean listChanged) {
    }

    record ResourceCapability(boolean subscribe, boolean listChanged) {
    }

    record PromptCapability(boolean listChanged) {
    }

    record ServerInfo(String name, String version) {
    }

    record ToolsListResult(List<McpToolView> tools) {
    }

    record McpToolView(String name, String description, Map<String, Object> inputSchema) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ToolCallResult(
            List<ContentBlock> content,
            Map<String, Object> structuredContent,
            @JsonProperty("isError") boolean isError) {
    }

    record ContentBlock(String type, String text) {
    }

    record ResourcesListResult(List<McpResourceView> resources) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record McpResourceView(String uri, String name, String description, String mimeType) {
    }

    record ResourceReadResult(List<ResourceContent> contents) {
    }

    record ResourceContent(String uri, String mimeType, String text) {
    }

    record PromptsListResult(List<McpPromptView> prompts) {
    }

    record McpPromptView(String name, String description, List<PromptArgument> arguments) {
    }

    record PromptArgument(String name, String description, boolean required) {
    }

    record PromptGetResult(String description, List<PromptMessage> messages) {
    }

    record PromptMessage(String role, PromptContent content) {
    }

    record PromptContent(String type, String text) {
    }

    record ScenarioPayload(
            String id,
            String title,
            String summary,
            String incidentSummary,
            List<ServiceSpec> services,
            List<Runbook> runbooks,
            int maxSteps) {

        static ScenarioPayload from(Scenario scenario) {
            return new ScenarioPayload(
                    scenario.id(),
                    scenario.title(),
                    scenario.summary(),
                    scenario.incidentSummary(),
                    scenario.services(),
                    scenario.runbooks(),
                    scenario.maxSteps());
        }
    }
}
