package io.ajentic.sre.agent.llm;

import io.ajentic.sre.agent.tool.ToolDescriptor;

import java.util.List;

public record LlmRequest(List<ChatMessage> messages, List<ToolDescriptor> tools, String model) {

    public LlmRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
