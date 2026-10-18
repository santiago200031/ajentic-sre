package io.ajentic.sre.agent.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes one argument of an {@link AgentPrompt} method.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface PromptParam {

    /** Human-readable description shown to the MCP host. */
    String value();

    /** Argument name on the wire. Defaults to the Java parameter name. */
    String name() default "";
}
