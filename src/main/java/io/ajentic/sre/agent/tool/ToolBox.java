package io.ajentic.sre.agent.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Stereotype;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a CDI bean whose {@link AgentTool} methods declare agent capabilities. The stereotype makes the bean
 * {@link ApplicationScoped} so it is a normal CDI bean, and {@link CdiToolRegistry} discovers it at startup.
 */
@Stereotype
@ApplicationScoped
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolBox {
}
