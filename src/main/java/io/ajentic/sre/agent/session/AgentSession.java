package io.ajentic.sre.agent.session;

import io.ajentic.sre.agent.approval.ApprovalOutcome;
import io.ajentic.sre.agent.approval.PendingApproval;
import io.ajentic.sre.agent.context.AgentExecutionContext;
import io.ajentic.sre.agent.llm.ChatMessage;
import io.ajentic.sre.agent.llm.LlmClient;
import io.ajentic.sre.agent.llm.LlmRequest;
import io.ajentic.sre.agent.llm.LlmResponse;
import io.ajentic.sre.agent.llm.ToolCall;
import io.ajentic.sre.agent.masking.MaskingSession;
import io.ajentic.sre.agent.masking.SensitiveDataMasker;
import io.ajentic.sre.agent.tool.AuthorizationDecision;
import io.ajentic.sre.agent.tool.Capability;
import io.ajentic.sre.agent.tool.Tool;
import io.ajentic.sre.agent.tool.ToolAuthorization;
import io.ajentic.sre.agent.tool.ToolContext;
import io.ajentic.sre.agent.tool.ToolInvocation;
import io.ajentic.sre.agent.tool.ToolRegistry;
import io.ajentic.sre.simulation.Environment;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
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
    private final Duration approvalTtl;
    private final Conversation conversation = new Conversation();
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, ApprovalOutcome> decidedApprovals = new ConcurrentHashMap<>();
    private final Map<String, PendingApproval> pendingThisTurn = new ConcurrentHashMap<>();

    public AgentSession(
            String id,
            AgentSessionConfig config,
            LlmClient llm,
            ToolRegistry tools,
            Environment environment,
            String systemPrompt) {
        this(id, config, llm, tools, environment, systemPrompt, tool -> AuthorizationDecision.ALLOW);
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
        this(id, config, llm, tools, environment, systemPrompt, authorization, maskingSession, meters,
                Duration.ofMinutes(10));
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
            MeterRegistry meters,
            Duration approvalTtl) {
        this.id = id;
        this.config = config;
        this.llm = llm;
        this.tools = tools;
        this.environment = environment;
        this.authorization = authorization;
        this.maskingSession = maskingSession;
        this.meters = meters;
        this.approvalTtl = approvalTtl;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            conversation.add(ChatMessage.system(systemPrompt));
        }
    }

    public String id() {
        return id;
    }

    public AgentSessionConfig config() {
        return config;
    }

    public Environment environment() {
        return environment;
    }

    public Conversation conversation() {
        return conversation;
    }

    public ChatTurnResult chat(String userMessage) {
        return chat(userMessage, TurnListener.NOOP, () -> false);
    }

    /**
     * Runs one chat turn, pushing {@link TurnEvent}s to {@code listener} as they are produced and stopping early
     * when {@code cancelled} reports that the client went away.
     */
    public ChatTurnResult chat(String userMessage, TurnListener listener, BooleanSupplier cancelled) {
        return ScopedValue.where(AgentExecutionContext.MASKING_SESSION, maskingSession)
                .call(() -> chatScoped(userMessage, listener, cancelled));
    }

    private ChatTurnResult chatScoped(String userMessage, TurnListener listener, BooleanSupplier cancelled) {
        expirePendingApprovals();
        conversation.add(ChatMessage.user(userMessage));
        List<ToolCall> lastToolCalls = List.of();
        Map<String, Integer> repeats = new ConcurrentHashMap<>();

        for (int iteration = 0; iteration < config.maxIterations(); iteration++) {
            if (cancelled.getAsBoolean()) {
                String answer = "Turn cancelled: the client disconnected before the agent finished.";
                conversation.add(ChatMessage.assistant(answer));
                emitDone(listener, answer);
                return new ChatTurnResult(answer, lastToolCalls, environment.observe(), conversation.usage());
            }
            if (overBudget()) {
                String answer = "Token budget exhausted after " + conversation.usage().totalTokens() + " tokens.";
                conversation.add(ChatMessage.assistant(answer));
                emitDone(listener, answer);
                return new ChatTurnResult(answer, lastToolCalls, environment.observe(), conversation.usage());
            }
            LlmResponse response = llm.complete(
                    new LlmRequest(conversation.messages(), tools.descriptors(), config.model()));
            recordTokens(response);
            conversation.addUsage(response.usage());
            listener.onEvent(TurnEvent.usage(
                    response.usage().promptTokens(), response.usage().completionTokens(), response.usage().totalTokens()));

            if (!response.hasToolCalls()) {
                String answer = response.content() == null ? "" : response.content();
                conversation.add(ChatMessage.assistant(answer));
                emitDone(listener, answer);
                return new ChatTurnResult(answer, lastToolCalls, environment.observe(), conversation.usage());
            }

            if (response.content() != null && !response.content().isBlank()) {
                listener.onEvent(TurnEvent.thought(response.content()));
            }
            conversation.add(ChatMessage.assistant(response.content(), response.toolCalls()));
            lastToolCalls = response.toolCalls();
            for (ToolCall call : response.toolCalls()) {
                listener.onEvent(TurnEvent.toolCall(call.name(), call.arguments()));
            }
            String[] results = new String[response.toolCalls().size()];
            if (executeAll(response.toolCalls(), repeats, results)) {
                String answer = "Stalled: repeated identical tool calls produced no new evidence. "
                        + "Try a different tool or different arguments.";
                conversation.add(ChatMessage.assistant(answer));
                emitDone(listener, answer);
                return new ChatTurnResult(answer, lastToolCalls, environment.observe(), conversation.usage());
            }
            for (int index = 0; index < response.toolCalls().size(); index++) {
                listener.onEvent(TurnEvent.toolResult(response.toolCalls().get(index).name(), results[index]));
            }
            registerPendingApprovals(response.toolCalls());

            if (environment.isTerminal()) {
                break;
            }
        }

        String answer = finalizeAnswer();
        emitDone(listener, answer);
        return new ChatTurnResult(answer, lastToolCalls, environment.observe(), conversation.usage());
    }

    private void emitDone(TurnListener listener, String answer) {
        listener.onEvent(TurnEvent.done(
                answer,
                conversation.usage().promptTokens(),
                conversation.usage().completionTokens(),
                conversation.usage().totalTokens()));
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

    private boolean overBudget() {
        return config.maxTokens() > 0 && conversation.usage().totalTokens() >= config.maxTokens();
    }

    private void recordTokens(LlmResponse response) {
        if (meters == null) {
            return;
        }
        String model = config.model() == null ? "default" : config.model();
        meters.counter("ajentic.llm.tokens", "kind", "prompt", "model", model)
                .increment(response.usage().promptTokens());
        meters.counter("ajentic.llm.tokens", "kind", "completion", "model", model)
                .increment(response.usage().completionTokens());
    }

    private boolean executeAll(List<ToolCall> toolCalls, Map<String, Integer> repeats, String[] results) {
        List<Integer> readOnly = new ArrayList<>();
        List<Integer> mutating = new ArrayList<>();
        boolean stalled = false;
        for (int index = 0; index < toolCalls.size(); index++) {
            ToolCall call = toolCalls.get(index);
            int seen = repeats.merge(call.name() + new TreeMap<>(call.arguments()), 1, Integer::sum);
            if (seen >= config.stagnationEscalateAfter()) {
                results[index] = "Stalled: " + call.name() + " was already called " + seen
                        + " times with the same arguments.";
                stalled = true;
            } else if (seen >= config.stagnationThreshold()) {
                results[index] = "You already called " + call.name()
                        + " with these arguments; try a different tool or arguments.";
            } else if (isReadOnly(call)) {
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
        return stalled;
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
        AuthorizationDecision decision = authorization.decide(tool.descriptor());
        if (decision == AuthorizationDecision.DENY) {
            return "Tool '%s' requires the %s capability and is not authorized."
                    .formatted(call.name(), tool.descriptor().capability());
        }
        if (decision == AuthorizationDecision.REQUIRE_APPROVAL) {
            return deferForApproval(call);
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

    private String deferForApproval(ToolCall call) {
        Instant now = Instant.now();
        PendingApproval approval = new PendingApproval(
                UUID.randomUUID().toString(), id, -1, call.id(), call.name(), call.arguments(), now,
                now.plus(approvalTtl));
        pendingThisTurn.put(call.id(), approval);
        return "PENDING_APPROVAL: " + call.name() + " is deferred behind operator approval (approval id "
                + approval.approvalId() + ", expires " + approval.expiresAt() + "). "
                + "Approve or deny it via POST /agent/sessions/" + id + "/approve; READ tools remain available.";
    }

    private void registerPendingApprovals(List<ToolCall> toolCalls) {
        int base = conversation.messages().size() - toolCalls.size();
        for (int index = 0; index < toolCalls.size(); index++) {
            PendingApproval approval = pendingThisTurn.remove(toolCalls.get(index).id());
            if (approval != null) {
                pendingApprovals.put(approval.approvalId(), approval.withIndex(base + index));
            }
        }
    }

    private void expirePendingApprovals() {
        Instant now = Instant.now();
        synchronized (pendingApprovals) {
            for (PendingApproval approval : pendingApprovals.entrySet().stream()
                    .map(Map.Entry::getValue).toList()) {
                if (approval.expiresAt().isBefore(now)) {
                    pendingApprovals.remove(approval.approvalId());
                    conversation.replace(approval.conversationIndex(),
                            ChatMessage.tool(approval.toolCallId(), "Approval expired before it was decided."));
                    decidedApprovals.put(approval.approvalId(),
                            new ApprovalOutcome(approval.approvalId(), false, false, "Approval expired before it was decided.", null));
                }
            }
        }
    }

    /**
     * Decides a pending approval exactly once. Repeated decisions are idempotent and return the stored outcome.
     */
    public ApprovalOutcome decideApproval(String approvalId, boolean approve, String note) {
        synchronized (pendingApprovals) {
            PendingApproval approval = pendingApprovals.remove(approvalId);
            if (approval == null) {
                return decidedApprovals.get(approvalId);
            }
            boolean expired = approval.expiresAt().isBefore(Instant.now());
            boolean executed = false;
            String result;
            if (approve && !expired) {
                result = executeDeferred(approval);
                executed = true;
            } else {
                result = expired
                        ? "Approval expired before it was decided."
                        : "Denied by operator" + (note == null || note.isBlank() ? "." : ": " + note.trim() + ".");
            }
            conversation.replace(approval.conversationIndex(), ChatMessage.tool(approval.toolCallId(), result));
            ApprovalOutcome outcome = new ApprovalOutcome(approval.approvalId(), approve, executed, result, note);
            decidedApprovals.put(approvalId, outcome);
            return outcome;
        }
    }

    private String executeDeferred(PendingApproval approval) {
        Tool tool = tools.find(approval.toolName()).orElse(null);
        if (tool == null) {
            return "Unknown tool: " + approval.toolName();
        }
        ToolCall call = new ToolCall(approval.toolCallId(), approval.toolName(), approval.arguments());
        ToolContext context = AgentExecutionContext.toolContext();
        if (context == null) {
            context = new ToolContext(id, environment);
        }
        long start = System.nanoTime();
        try {
            var result = tool.invoke(new ToolInvocation(approval.toolName(), approval.arguments()), context);
            return result.success() ? result.content() : "Tool error: " + result.content();
        } finally {
            if (meters != null) {
                meters.timer("ajentic.tool.latency", "tool", approval.toolName())
                        .record(Duration.ofNanos(System.nanoTime() - start));
                meters.counter("ajentic.tool.invocations",
                        "tool", approval.toolName(),
                        "capability", tool.descriptor().capability().name()).increment();
            }
        }
    }

    /**
     * The approvals that are still awaiting an operator decision.
     */
    public List<PendingApproval> pendingApprovals() {
        expirePendingApprovals();
        return List.copyOf(pendingApprovals.values());
    }

    private String finalizeAnswer() {
        String answer = environment.isTerminal()
                ? "Incident resolved. " + environment.observe().feedback()
                : "Reached the maximum number of tool iterations for this turn.";
        conversation.add(ChatMessage.assistant(answer));
        return answer;
    }
}
