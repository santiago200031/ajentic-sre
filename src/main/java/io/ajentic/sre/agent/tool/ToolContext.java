package io.ajentic.sre.agent.tool;

import io.ajentic.sre.rl.Environment;

public record ToolContext(String sessionId, Environment environment) {
}
