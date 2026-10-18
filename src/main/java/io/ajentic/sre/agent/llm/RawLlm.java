package io.ajentic.sre.agent.llm;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the unmasked {@link LlmClient} produced by {@link LlmClientProducer}. The masking decorator injects this
 * one so it does not recurse into itself.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE})
public @interface RawLlm {
}
