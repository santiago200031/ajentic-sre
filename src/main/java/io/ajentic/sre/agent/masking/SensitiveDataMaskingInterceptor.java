package io.ajentic.sre.agent.masking;

import io.ajentic.sre.agent.context.AgentExecutionContext;
import io.ajentic.sre.agent.llm.ChatMessage;
import io.ajentic.sre.agent.llm.LlmRequest;
import io.ajentic.sre.agent.llm.LlmResponse;
import io.ajentic.sre.agent.llm.ToolCall;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.util.List;

/**
 * Cross-cutting guardrail for LLM traffic. For each {@link LlmRequest} it opens a {@link MaskingSession}, replaces
 * identifiers in the outgoing messages, binds the session as a scoped value, and restores the originals in the
 * response text and tool-call arguments. When the agent has already bound a session for its whole conversation, the
 * interceptor reuses it so placeholders stay stable across turns.
 */
@MaskSensitiveData
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class SensitiveDataMaskingInterceptor {

    @Inject
    SensitiveDataMasker masker;

    @AroundInvoke
    Object mask(InvocationContext context) throws Exception {
        Object[] parameters = context.getParameters();
        if (parameters.length != 1 || !(parameters[0] instanceof LlmRequest request)) {
            return context.proceed();
        }
        MaskingSession bound = AgentExecutionContext.maskingSession();
        MaskingSession session = bound == null ? masker.newSession() : bound;
        context.setParameters(new Object[]{maskRequest(session, request)});
        LlmResponse response = bound == null
                ? ScopedValue.where(AgentExecutionContext.MASKING_SESSION, session)
                        .call(() -> (LlmResponse) context.proceed())
                : (LlmResponse) context.proceed();
        return unmaskResponse(session, response);
    }

    private static LlmRequest maskRequest(MaskingSession session, LlmRequest request) {
        List<ChatMessage> masked = request.messages().stream()
                .map(message -> new ChatMessage(
                        message.role(),
                        session.mask(message.content()),
                        maskToolCalls(session, message.toolCalls()),
                        message.toolCallId()))
                .toList();
        return new LlmRequest(masked, request.tools(), request.model());
    }

    private static LlmResponse unmaskResponse(MaskingSession session, LlmResponse response) {
        return new LlmResponse(
                session.unmask(response.content()),
                unmaskToolCalls(session, response.toolCalls()),
                response.usage(),
                response.finishReason());
    }

    private static List<ToolCall> maskToolCalls(MaskingSession session, List<ToolCall> toolCalls) {
        return toolCalls.stream()
                .map(call -> new ToolCall(call.id(), call.name(), session.maskArguments(call.arguments())))
                .toList();
    }

    private static List<ToolCall> unmaskToolCalls(MaskingSession session, List<ToolCall> toolCalls) {
        return toolCalls.stream()
                .map(call -> new ToolCall(call.id(), call.name(), session.unmaskArguments(call.arguments())))
                .toList();
    }
}
