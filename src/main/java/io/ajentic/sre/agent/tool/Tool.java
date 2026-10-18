package io.ajentic.sre.agent.tool;

public interface Tool {

    ToolDescriptor descriptor();

    ToolResult invoke(ToolInvocation invocation, ToolContext context);
}
