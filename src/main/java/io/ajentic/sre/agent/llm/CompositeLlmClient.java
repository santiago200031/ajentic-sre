package io.ajentic.sre.agent.llm;

import java.util.List;

/**
 * Fails over, in order, to the configured fallback endpoints whenever the primary client fails with a transport
 * error, an upstream 4xx/5xx, an unparseable response, or a context-overflow rejection. Masking is applied once
 * around this composite by {@link MaskingLlmClient}, so failover cannot bypass redaction.
 */
public final class CompositeLlmClient implements LlmClient {

    private final LlmClient primary;
    private final List<LlmClient> fallbacks;

    public CompositeLlmClient(LlmClient primary, List<LlmClient> fallbacks) {
        this.primary = primary;
        this.fallbacks = List.copyOf(fallbacks);
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        LlmException failure = null;
        try {
            return primary.complete(request);
        } catch (LlmException exception) {
            failure = exception;
        }
        for (LlmClient fallback : fallbacks) {
            try {
                return fallback.complete(request);
            } catch (LlmException exception) {
                failure = exception;
            }
        }
        throw new LlmException("All LLM endpoints failed. Last error: " + failure.getMessage(), failure);
    }
}
