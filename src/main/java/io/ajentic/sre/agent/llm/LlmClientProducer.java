package io.ajentic.sre.agent.llm;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

@ApplicationScoped
public class LlmClientProducer {

    @Inject
    ChatModel chatModel;

    /**
     * The unmasked client for the configured provider. {@link MaskingLlmClient} injects this under {@link RawLlm}
     * and adds the masking guardrail. When {@code ajentic.llm.fallback-enabled} is set with at least one
     * fallback endpoint, the client is a {@link CompositeLlmClient} that fails over in endpoint order.
     */
    @Produces
    @RawLlm
    @Singleton
    public LlmClient rawClient(LlmProperties properties, ObjectMapper mapper) {
        LlmClient primary = properties.providerType().usesHttpTransport()
                ? new LangChain4jLlmClient(chatModel, mapper)
                : new ScriptedLlmClient();
        if (!properties.fallbackEnabled() || properties.fallbackEndpoints().isEmpty()) {
            return primary;
        }
        java.util.List<LlmClient> fallbacks = java.util.Arrays.stream(properties.fallbackEndpoints().get().split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .<LlmClient>map(entry -> new LangChain4jLlmClient(endpointProperties(entry, properties), mapper))
                .toList();
        return new CompositeLlmClient(primary, fallbacks);
    }

    /**
     * Parses one pipe-separated fallback entry: {@code base-url|model|api-key|api-version|api-key-header}.
     * Fields after the base URL may be empty and inherit sensible defaults.
     */
    private static LlmProperties endpointProperties(String entry, LlmProperties primary) {
        String[] parts = entry.split("\\|", -1);
        String model = parts.length > 1 && !parts[1].isBlank() ? parts[1] : primary.model();
        return LlmProperties.of(
                "openai-compatible", parts[0],
                optional(parts.length > 2 ? parts[2] : ""), model,
                primary.timeout(), primary.temperature(), primary.chatCompletionsPath(),
                optional(parts.length > 3 ? parts[3] : ""),
                optional(parts.length > 4 ? parts[4] : ""));
    }

    private static Optional<String> optional(String value) {
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
