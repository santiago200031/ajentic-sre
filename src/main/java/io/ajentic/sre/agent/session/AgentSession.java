package io.ajentic.sre.agent.session;

import io.ajentic.sre.agent.context.AgentExecutionContext;
import io.ajentic.sre.agent.llm.ChatMessage;
import io.ajentic.sre.agent.llm.LlmClient;
import io.ajentic.sre.agent.llm.LlmRequest;
import io.ajentic.sre.agent.llm.LlmResponse;
import io.ajentic.sre.agent.llm.ToolCall;
import io.ajentic.sre.agent.masking.MaskingSession;
import io.ajentic.sre.agent.masking.SensitiveDataMasker;
import io.ajentic.sre.agent.tool.Capability;
import io.ajentic.sre.agent.tool.Tool;
import io.ajentic.sre.agent.tool.ToolAuthorization;
import io.ajentic.sre.agent.tool.ToolContext;
import io.ajentic.sre.agent.tool.ToolInvocation;
import io.ajentic.sre.agent.tool.ToolRegistry;
import io.ajentic.sre.rl.Environment;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Gatherers;

public final class AgentSession {

    private static final int MAX_PARALLEL_READS = 8;

    private final String id;
    private final AgentSessionConfig config;
    private final LlmClient llm;
    private final ToolRegistry tools;
    private final Environment environment;
    private final ToolAuthorization authorization;
    private final MaskingSession maskingSession;
    private final MeterRegistry meters;
    private final Conversation conversation = new Conversation();

    public AgentSession(
            String id,
            AgentSessionConfig config,
            LlmClient llm,
            ToolRegistry tools,
            Environment environment,
            String systemPrompt) {
        this(id, config, llm, tools, environment, systemPrompt, tool -> true);
    }

    public AgentSession(
            String id,
            AgentSessionConfig config,
            LlmClient llm,
            ToolRegistry tools,
            Environment environment,
            String systemPrompt,
            ToolAuthorization authorization) {
        this(id, config, llm, tools, environment, systemPrompt, authorization,
                new SensitiveDataMasker().newSession());
    }

    public AgentSession(
            String id,
            AgentSessionConfig config,
            LlmClient llm,
            ToolRegistry tools,
            Environment environment,
            String systemPrompt,
            ToolAuthorization authorization,
            MaskingSession maskingSession) {
        this(id, config, llm, tools, environment, systemPrompt, authorization, maskingSession, null);
    }

    public AgentSession(
            String id,
            AgentSessionConfig config,
            LlmClient llm,
            ToolRegistry tools,
            Environment environment,
            String systemPrompt,
            ToolAuthorization authorization,
            MaskingSession maskingSession,
            MeterRegistry meters) {
        this.id = id;
        this.config = config;
        this.llm = llm;
        this.tools = tools;
        this.environment = environment;
        this.authorization = authorization;
        this.maskingSession = maskingSession;
        this.meters = meters;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            conversation.add(ChatMessage.system(systemPrompt));
        }
    }

    public String id() {
        return id;
    }

    public Environment environment() {
        return environment;
    }

    public Conversation conversation() {
        return conversation;
    }

    public ChatTurnResult chat(String userMessage) {
        return ScopedValue.where(AgentExecutionContext.MASKING_SESSION, maskingSession)
                .call(() -> chatScoped(userMessage));
    }

    private ChatTurnResult chatScoped(String userMessage) {
        conversation.add(ChatMessage.user(userMessage));
        List<ToolCall> lastToolCalls = List.of();

        for (int iteration = 0; iteration < config.maxIterations(); iteration++) {
            LlmResponse response = llm.complete(
                    new LlmRequest(conversation.messages(), tools.descriptors(), config.model()));
            recordTokens(response);

            if (!response.hasToolCalls()) {
                String answer = response.content() == null ? "" : response.content();
                conversation.add(ChatMessage.assistant(answer));
                return new ChatTurnResult(answer, lastToolCalls, environment.observe());
            }

            conversation.add(ChatMessage.assistant(response.content(), response.toolCalls()));
            lastToolCalls = response.toolCalls();
            executeAll(response.toolCalls());

            if (environment.isTerminal()) {
                break;
            }
        }

        return new ChatTurnResult(finalizeAnswer(), lastToolCalls, environment.observe());
    }

    public ChatTurnResult chatUntilGoal(String userMessage, Goal goal) {
        ChatTurnResult turn = chat(userMessage);
        int guard = 0;
        while (!goal.completion().test(conversation) && !environment.isTerminal()
                && guard++ < config.maxIterations()) {
            turn = chat("Continue until the goal is met: " + goal.description());
        }
        return turn;
    }

    private void recordTokens(LlmResponse response) {
        if (meters == null) {
            return;
        }
        String model = config.model();
        meters.counter("ajentic.llm.tokens", "kind", "prompt", "model", model)
                .increment(response.usage().promptTokens());
        meters.counter("ajentic.llm.tokens", "kind", "completion", "model", model)
                .increment(response.usage().completionTokens());
    }

    private void executeAll(List<ToolCall> toolCalls) {
        String[] results = new String[toolCalls.size()];
        List<Integer> readOnly = new ArrayList<>();
        List<Integer> mutating = new ArrayList<>();
        for (int index = 0; index < toolCalls.size(); index++) {
            if (isReadOnly(toolCalls.get(index))) {
                readOnly.add(index);
            } else {
                mutating.add(index);
            }
        }
        runReadOnly(toolCalls, readOnly, results);
        for (int index : mutating) {
            results[index] = execute(toolCalls.get(index));
        }
        for (int index = 0; index < toolCalls.size(); index++) {
            conversation.add(ChatMessage.tool(toolCalls.get(index).id(), results[index]));
        }
    }

    private void runReadOnly(List<ToolCall> toolCalls, List<Integer> indexes, String[] results) {
        if (indexes.isEmpty()) {
            return;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            indexes.stream()
                    .gather(Gatherers.windowFixed(MAX_PARALLEL_READS))
                    .forEach(batch -> executeBatch(executor, toolCalls, batch, results));
        }
    }

    private void executeBatch(ExecutorService executor, List<ToolCall> toolCalls, List<Integer> batch,
            String[] results) {
        List<Future<?>> futures = new ArrayList<>(batch.size());
        for (int index : batch) {
            Future<?> future = executor.submit(() -> executeScoped(toolCalls.get(index), index, results));
            futures.add(future);
        }
        futures.forEach(AgentSession::await);
    }

    private void executeScoped(ToolCall call, int index, String[] results) {
        ToolContext context = new ToolContext(id, environment);
        ScopedValue.where(AgentExecutionContext.SESSION_ID, id)
                .where(AgentExecutionContext.TOOL_CONTEXT, context)
                .run(() -> results[index] = execute(call));
    }

    private static void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Parallel tool execution interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Parallel tool execution failed", exception.getCause());
        }
    }

    private boolean isReadOnly(ToolCall call) {
        return tools.find(call.name())
                .map(tool -> tool.descriptor().capability() == Capability.READ)
                .orElse(false);
    }

    private String execute(ToolCall call) {
        return tools.find(call.name())
                .map(tool -> invoke(tool, call))
                .orElse("Unknown tool: " + call.name());
    }

    private String invoke(Tool tool, ToolCall call) {
        if (!authorization.isAuthorized(tool.descriptor())) {
            return "Tool '%s' requires the %s capability and is not authorized."
                    .formatted(call.name(), tool.descriptor().capability());
        }
        ToolContext context = AgentExecutionContext.toolContext();
        if (context == null) {
            context = new ToolContext(id, environment);
        }
        long start = System.nanoTime();
        try {
            var result = tool.invoke(new ToolInvocation(call.name(), call.arguments()), context);
            return result.success() ? result.content() : "Tool error: " + result.content();
        } finally {
            if (meters != null) {
                meters.timer("ajentic.tool.latency", "tool", call.name())
                        .record(Duration.ofNanos(System.nanoTime() - start));
                meters.counter("ajentic.tool.invocations",
                        "tool", call.name(),
                        "capability", tool.descriptor().capability().name()).increment();
            }
        }
    }

    private String finalizeAnswer() {
        String answer = environment.isTerminal()
                ? "Incident resolved. " + environment.observe().feedback()
                : "Reached the maximum number of tool iterations for this turn.";
        conversation.add(ChatMessage.assistant(answer));
        return answer;
    }
}
