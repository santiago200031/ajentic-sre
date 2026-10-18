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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-cutting guardrail for LLM traffic. For each {@link LlmRequest} it opens a {@link MaskingSession}, replaces
 * identifiers in the outgoing messages, binds the session as a scoped value, and restores the originals in the
 * response text and tool-call arguments.
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
        MaskingSession session = masker.newSession();
        context.setParameters(new Object[]{maskRequest(session, request)});
        LlmResponse response = ScopedValue.where(AgentExecutionContext.MASKING_SESSION, session)
                .call(() -> (LlmResponse) context.proceed());
        return unmaskResponse(session, response);
    }

    private static LlmRequest maskRequest(MaskingSession session, LlmRequest request) {
        List<ChatMessage> masked = request.messages().stream()
                .map(message -> new ChatMessage(
                        message.role(),
                        session.mask(message.content()),
                        message.toolCalls(),
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

    private static List<ToolCall> unmaskToolCalls(MaskingSession session, List<ToolCall> toolCalls) {
        return toolCalls.stream()
                .map(call -> new ToolCall(call.id(), call.name(), unmaskArguments(session, call.arguments())))
                .toList();
    }

    private static Map<String, Object> unmaskArguments(MaskingSession session, Map<String, Object> arguments) {
        Map<String, Object> unmasked = new LinkedHashMap<>();
        arguments.forEach((key, value) -> unmasked.put(
                key,
                value instanceof String text ? session.unmask(text) : value));
        return unmasked;
    }
}
