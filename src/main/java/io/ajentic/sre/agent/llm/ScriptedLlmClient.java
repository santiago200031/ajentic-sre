package io.ajentic.sre.agent.llm;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ScriptedLlmClient implements LlmClient {

    private final Deque<LlmResponse> scripted = new ArrayDeque<>();
    private final LlmResponse fallback;

    public ScriptedLlmClient() {
        this(LlmResponse.finalAnswer("No further action required."));
    }

    public ScriptedLlmClient(LlmResponse fallback) {
        this.fallback = fallback;
    }

    public ScriptedLlmClient enqueue(LlmResponse response) {
        scripted.add(response);
        return this;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        return scripted.isEmpty() ? fallback : scripted.poll();
    }
}
