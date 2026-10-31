package io.ajentic.sre.agent.mcp;

import io.ajentic.sre.rl.simulation.Scenario;
import io.ajentic.sre.rl.simulation.ScenarioRepository;
import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.PromptResponse;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The reusable prompt templates the MCP server publishes.
 */
@ApplicationScoped
@Unremovable
public class SreAgentPrompts {

    private final ScenarioRepository scenarios;

    @Inject
    public SreAgentPrompts(ScenarioRepository scenarios) {
        this.scenarios = scenarios;
    }

    @Prompt(name = "investigate-incident", description = "Bootstrap an investigation of a scenario.")
    public PromptResponse investigateIncident(
            @PromptArg(name = "scenarioId", description = "Scenario identifier.", required = true) String scenarioId) {
        Scenario scenario = require(scenarioId);
        String text = ("Investigate the '%s' incident (%s). Query metrics, logs, traces and "
                + "deployments, identify the root cause, apply the correct remediation, and record a diagnosis.")
                .formatted(scenario.id(), scenario.incidentSummary());
        return PromptResponse.withMessages(PromptMessage.withUserRole(text));
    }

    @Prompt(name = "summarize-postmortem", description = "Draft a postmortem summary for a scenario.")
    public PromptResponse summarizePostmortem(
            @PromptArg(name = "scenarioId", description = "Scenario identifier.", required = true) String scenarioId) {
        Scenario scenario = require(scenarioId);
        String text = ("Write a concise postmortem for the '%s' incident. Cover impact, root "
                + "cause, remediation, and follow-up actions.").formatted(scenario.id());
        return PromptResponse.withMessages(PromptMessage.withUserRole(text));
    }

    @Prompt(name = "code-remediation", description = "Bootstrap automated code fix and pull request for an alert.")
    public PromptResponse codeRemediation(
            @PromptArg(name = "repository", description = "Repository owner and name.", required = true) String repository,
            @PromptArg(name = "branch", description = "Target branch name.", required = false) String branch,
            @PromptArg(name = "title", description = "Alert title.", required = true) String title,
            @PromptArg(name = "message", description = "Alert message.", required = true) String message,
            @PromptArg(name = "stackTrace", description = "Stack trace if available.", required = false) String stackTrace,
            @PromptArg(name = "fingerprint", description = "Alert fingerprint.", required = false) String fingerprint) {
        String text = buildCodeRemediationPrompt(repository, branch, title, message, stackTrace, fingerprint);
        return PromptResponse.withMessages(PromptMessage.withUserRole(text));
    }

    public static String buildCodeRemediationPrompt(
            String repository,
            String branch,
            String title,
            String message,
            String stackTrace,
            String fingerprint) {
        String fp8 = fingerprint != null && fingerprint.length() >= 8 ? fingerprint.substring(0, 8) : "12345678";
        String fixBranch = "ajentic/fix-" + fp8;
        String baseBranch = branch != null && !branch.isBlank() ? branch : "main";
        return """
                Investigate and remediate the following alert in repository %s:
                Title: %s
                Message: %s
                Stack Trace:
                %s

                Execute the following remediation steps:
                1. Read the stack trace carefully to locate the failing file and line.
                2. Use github_read_file to inspect the named source file and its nearest test file on branch %s.
                3. State the root cause in one concise paragraph.
                4. Use github_create_branch to create branch %s from %s.
                5. Use github_commit_file to commit the minimal fix AND a regression test to %s.
                6. Use github_create_pull_request to open a PR immediately into %s with the root cause and diff summary.
                7. Use github_get_ci_status to check the initial CI snapshot on the new commit (report CI pending if not started yet).

                Strict constraints:
                - Do NOT touch unrelated files.
                - Do NOT reformat code.
                - Do NOT bump dependencies.
                """.formatted(repository,
                title != null ? title : "(none)",
                message != null ? message : "(none)",
                stackTrace != null && !stackTrace.isBlank() ? stackTrace : "(no stack trace provided)",
                baseBranch, fixBranch, baseBranch, fixBranch, baseBranch);
    }

    private Scenario require(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("Missing required prompt argument 'scenarioId'");
        }
        return scenarios.byId(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + scenarioId));
    }
}
