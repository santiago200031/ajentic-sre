package io.ajentic.sre.agent.context;

import io.ajentic.sre.agent.masking.MaskingSession;
import io.ajentic.sre.agent.tool.ToolContext;

/**
 * Scoped values that follow a request across the virtual threads it fans out to while the agent investigates an
 * incident. Bind them with {@code ScopedValue.where(...).run(...)} before dispatching parallel work.
 */
public final class AgentExecutionContext {

    public static final ScopedValue<String> SESSION_ID = ScopedValue.newInstance();

    public static final ScopedValue<MaskingSession> MASKING_SESSION = ScopedValue.newInstance();

    public static final ScopedValue<ToolContext> TOOL_CONTEXT = ScopedValue.newInstance();

    private AgentExecutionContext() {
    }

    public static String sessionId() {
        return SESSION_ID.isBound() ? SESSION_ID.get() : null;
    }

    public static MaskingSession maskingSession() {
        return MASKING_SESSION.isBound() ? MASKING_SESSION.get() : null;
    }

    public static ToolContext toolContext() {
        return TOOL_CONTEXT.isBound() ? TOOL_CONTEXT.get() : null;
    }
}
