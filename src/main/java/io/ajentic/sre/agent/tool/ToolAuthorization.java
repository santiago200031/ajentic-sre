package io.ajentic.sre.agent.tool;

@FunctionalInterface
public interface ToolAuthorization {

    boolean isAuthorized(ToolDescriptor tool);
}
