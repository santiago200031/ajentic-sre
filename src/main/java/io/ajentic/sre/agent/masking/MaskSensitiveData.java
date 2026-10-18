package io.ajentic.sre.agent.masking;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds {@link SensitiveDataMaskingInterceptor} to a bean. Applied to the LLM client so every request is masked
 * before it leaves the process and unmasked on the way back.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface MaskSensitiveData {
}
