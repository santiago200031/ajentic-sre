package io.ajentic.sre.agent.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one Model Context Protocol prompt on a CDI bean. {@link PromptCatalog} discovers these methods, exposes
 * them through {@code prompts/list}, and renders them for {@code prompts/get}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentPrompt {

    /** Prompt name exposed to the MCP host. */
    String name();

    /** Human-readable description shown to the MCP host. */
    String description();
}
