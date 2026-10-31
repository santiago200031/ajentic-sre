package io.ajentic.sre.agent.tool;

import io.ajentic.sre.simulation.Environment;

public record ToolContext(String sessionId, Environment environment) {
}
