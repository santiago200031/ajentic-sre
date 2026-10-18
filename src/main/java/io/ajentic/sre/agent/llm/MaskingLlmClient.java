package io.ajentic.sre.agent.llm;

import io.ajentic.sre.agent.masking.MaskSensitiveData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The application's LLM entry point. It delegates to the raw client and relies on
 * {@link io.ajentic.sre.agent.masking.SensitiveDataMaskingInterceptor} to redact identifiers on the way out and
 * restore them on the way back.
 */
@ApplicationScoped
@MaskSensitiveData
public class MaskingLlmClient implements LlmClient {

    private final LlmClient delegate;

    @Inject
    public MaskingLlmClient(@RawLlm LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        return delegate.complete(request);
    }
}
