package io.ajentic.sre.agent.llm;

public interface LlmClient {

    LlmResponse complete(LlmRequest request);
}
