package io.ajentic.sre.agent.session;

import io.ajentic.sre.agent.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered transcript of a session. Reads and writes are synchronized so a session can be shared by concurrent
 * HTTP requests and the virtual threads that fan out parallel tool calls.
 */
public final class Conversation {

    private final List<ChatMessage> messages = new ArrayList<>();

    public synchronized void add(ChatMessage message) {
        messages.add(message);
    }

    public synchronized List<ChatMessage> messages() {
        return List.copyOf(messages);
    }
}
