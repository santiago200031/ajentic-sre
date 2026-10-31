package io.ajentic.sre.agent.tool;

/**
 * Decides, per tool, whether a call may run immediately, must be blocked, or is deferred behind operator
 * approval.
 */
public interface ToolAuthorization {

    AuthorizationDecision decide(ToolDescriptor tool);

    /**
     * Backward-compatible view: only unconditionally allowed calls pass.
     */
    default boolean isAuthorized(ToolDescriptor tool) {
        return decide(tool) == AuthorizationDecision.ALLOW;
    }
}
