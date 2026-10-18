package io.ajentic.sre.agent.llm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import tools.jackson.databind.ObjectMapper;

@ApplicationScoped
public class LlmClientProducer {

    /**
     * The unmasked client for the configured provider. {@link MaskingLlmClient} injects this under {@link RawLlm}
     * and adds the masking guardrail.
     */
    @Produces
    @RawLlm
    @Singleton
    public LlmClient rawClient(LlmProperties properties, ObjectMapper mapper) {
        return properties.providerType().usesHttpTransport()
                ? new OpenAiCompatibleLlmClient(properties, mapper)
                : new ScriptedLlmClient();
    }
}
