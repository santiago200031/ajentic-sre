package io.ajentic.sre.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes one argument of an {@link AgentTool} method. Any parameter that is not a {@link ToolContext} must carry
 * this annotation so the registry can name it and document it in the generated JSON Schema.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {

    /** Human-readable description shown to the model. */
    String value();

    /** Whether the model must supply the argument. */
    boolean required() default true;

    /** Value used when the model omits the argument; an empty string means none. */
    String defaultValue() default "";
}
