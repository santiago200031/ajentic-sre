package io.ajentic.sre.agent.session;

import io.ajentic.sre.agent.llm.ChatMessage;
import io.ajentic.sre.agent.llm.TokenUsage;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered transcript of a session. Reads and writes are synchronized so a session can be shared by concurrent
 * HTTP requests and the virtual threads that fan out parallel tool calls.
 */
public final class Conversation {

    private final List<ChatMessage> messages = new ArrayList<>();
    private TokenUsage usage = TokenUsage.empty();

    public synchronized void add(ChatMessage message) {
        messages.add(message);
    }

    public synchronized void addUsage(TokenUsage used) {
        usage = usage.plus(used);
    }

    /**
     * Replaces the message at {@code index} (used to swap a pending-approval placeholder for the real tool
     * result once the operator decides).
     */
    public synchronized void replace(int index, ChatMessage message) {
        messages.set(index, message);
    }

    public synchronized TokenUsage usage() {
        return usage;
    }

    public synchronized List<ChatMessage> messages() {
        return List.copyOf(messages);
    }
}
