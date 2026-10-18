package io.ajentic.sre.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one agent tool. The parameter types and {@link ToolParam} metadata are turned into the JSON Schema the
 * model sees, and invocation arguments are coerced back into those types before the method runs.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {

    /** Tool name exposed to the model. Defaults to the Java method name. */
    String name() default "";

    /** Human-readable description shown to the model. */
    String description();

    /** Whether the tool only reads state or mutates it. */
    Capability capability() default Capability.READ;
}
