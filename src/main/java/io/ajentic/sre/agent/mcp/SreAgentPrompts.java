package io.ajentic.sre.agent.mcp;

import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ScenarioRepository;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The reusable prompt templates the MCP server publishes. Each method is discovered through {@link AgentPrompt},
 * so the protocol handler never hardcodes prompt text or argument handling.
 */
@ApplicationScoped
@Unremovable
public class SreAgentPrompts {

    private final ScenarioRepository scenarios;

    @Inject
    public SreAgentPrompts(ScenarioRepository scenarios) {
        this.scenarios = scenarios;
    }

    @AgentPrompt(name = "investigate-incident", description = "Bootstrap an investigation of a scenario.")
    public String investigateIncident(@PromptParam("Scenario identifier.") String scenarioId) {
        Scenario scenario = require(scenarioId);
        return ("Investigate the '%s' incident (%s). Query metrics, logs, traces and "
                + "deployments, identify the root cause, apply the correct remediation, and record a diagnosis.")
                .formatted(scenario.id(), scenario.incidentSummary());
    }

    @AgentPrompt(name = "summarize-postmortem", description = "Draft a postmortem summary for a scenario.")
    public String summarizePostmortem(@PromptParam("Scenario identifier.") String scenarioId) {
        Scenario scenario = require(scenarioId);
        return ("Write a concise postmortem for the '%s' incident. Cover impact, root "
                + "cause, remediation, and follow-up actions.").formatted(scenario.id());
    }

    private Scenario require(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("Missing required prompt argument 'scenarioId'");
        }
        return scenarios.byId(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + scenarioId));
    }
}
