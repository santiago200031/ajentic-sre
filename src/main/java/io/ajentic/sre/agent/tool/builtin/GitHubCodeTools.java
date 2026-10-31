package io.ajentic.sre.agent.tool.builtin;

import io.ajentic.sre.agent.github.GitHubClient;
import io.ajentic.sre.agent.github.GitHubException;
import io.ajentic.sre.agent.tool.AgentTool;
import io.ajentic.sre.agent.tool.Capability;
import io.ajentic.sre.agent.tool.ToolBox;
import io.ajentic.sre.agent.tool.ToolParam;
import io.ajentic.sre.agent.tool.ToolResult;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;

/**
 * Zero-clone GitHub capabilities for the code-remediation track. The READ tools inspect an external repository over the
 * GitHub REST API; the MUTATE tools open a fix branch, commit a patch plus a regression test, and open a Pull Request.
 * {@link CdiToolRegistry} discovers this {@link ToolBox} automatically. When no token is configured the MUTATE tools
 * report it instead of failing, so analysis-only turns still work.
 */
@ToolBox
@Unremovable
public class GitHubCodeTools {

    public static final String TOOL_GITHUB_LIST_FILES = "github_list_files";
    public static final String TOOL_GITHUB_READ_FILE = "github_read_file";
    public static final String TOOL_GITHUB_CREATE_BRANCH = "github_create_branch";
    public static final String TOOL_GITHUB_COMMIT_FILE = "github_commit_file";
    public static final String TOOL_GITHUB_GET_CI_STATUS = "github_get_ci_status";
    public static final String TOOL_GITHUB_CREATE_PULL_REQUEST = "github_create_pull_request";
    public static final String TOOL_GITHUB_TRIGGER_WORKFLOW = "github_trigger_workflow";

    public static final String PARAM_REPO = "repo";
    public static final String PARAM_BRANCH = "branch";
    public static final String PARAM_NEW_BRANCH = "newBranch";
    public static final String PARAM_BASE_BRANCH = "baseBranch";
    public static final String PARAM_PATH = "path";
    public static final String PARAM_CONTENT = "content";
    public static final String PARAM_MESSAGE = "message";
    public static final String PARAM_EXISTING_SHA = "existingSha";
    public static final String PARAM_TITLE = "title";
    public static final String PARAM_BODY = "body";
    public static final String PARAM_HEAD = "head";
    public static final String PARAM_BASE = "base";
    public static final String PARAM_WORKFLOW_ID = "workflowId";
    public static final String PARAM_REF = "ref";

    private final GitHubClient github;
    private final boolean triggerWorkflowsEnabled;

    @Inject
    public GitHubCodeTools(
            GitHubClient github,
            @ConfigProperty(name = "ajentic.github.ci.trigger-workflows", defaultValue = "false")
            boolean triggerWorkflowsEnabled) {
        this.github = github;
        this.triggerWorkflowsEnabled = triggerWorkflowsEnabled;
    }

    public GitHubCodeTools(GitHubClient github) {
        this(github, false);
    }

    @AgentTool(name = TOOL_GITHUB_LIST_FILES, description = "List the files in an external GitHub repository at a ref.",
            capability = Capability.READ)
    public ToolResult listFiles(
            @ToolParam("Repository as owner/name, for example acme/checkout-service.") String repo,
            @ToolParam(value = "Branch or commit sha to list; defaults to main.", required = false, defaultValue = "main")
            String ref,
            @ToolParam(value = "Optional path prefix to filter, for example src/main/java.", required = false)
            String pathPrefix) {
        try {
            List<String> files = github.listFiles(repo, ref, pathPrefix);
            if (files.isEmpty()) {
                return ToolResult.ok("No files found under '" + pathPrefix + "' in " + repo + " at " + ref + ".");
            }
            String text = files.stream()
                    .map(file -> "- " + file)
                    .reduce((first, second) -> first + "\n" + second)
                    .orElse("");
            return ToolResult.ok(text, Map.of("count", files.size()));
        } catch (GitHubException error) {
            return ToolResult.error(error.getMessage());
        }
    }

    @AgentTool(name = TOOL_GITHUB_READ_FILE, description = "Read the decoded content of a file in an external repo.",
            capability = Capability.READ)
    public ToolResult readFile(
            @ToolParam("Repository as owner/name, for example acme/checkout-service.") String repo,
            @ToolParam("File path within the repository, for example src/main/java/App.java.") String path,
            @ToolParam(value = "Branch or commit sha to read; defaults to main.", required = false, defaultValue = "main")
            String ref) {
        try {
            return github.readFile(repo, path, ref).map(content -> ToolResult.ok(
                    "=== " + path + " (sha " + content.sha() + ") ===\n" + content.content(),
                    Map.of("path", path, "sha", content.sha())))
                    .orElseGet(() -> ToolResult.error("'" + path + "' is a directory, not a file, in " + repo + "."));
        } catch (GitHubException error) {
            return ToolResult.error(error.getMessage());
        }
    }

    @AgentTool(name = TOOL_GITHUB_GET_CI_STATUS,
            description = "Report the GitHub Actions check runs for a commit sha.", capability = Capability.READ)
    public ToolResult ciStatus(
            @ToolParam("Repository as owner/name, for example acme/checkout-service.") String repo,
            @ToolParam("Commit sha to report check runs for.") String ref) {
        try {
            GitHubClient.CiStatus status = github.getCiStatus(repo, ref);
            if (status.runs().isEmpty()) {
                return ToolResult.ok("No check runs recorded for " + ref + " yet.", Map.of("total", status.totalCount()));
            }
            String text = status.runs().stream()
                    .map(run -> "- %s: %s / %s".formatted(run.name(), run.status(), run.conclusion()))
                    .reduce((first, second) -> first + "\n" + second)
                    .orElse("");
            return ToolResult.ok(text, Map.of("total", status.totalCount()));
        } catch (GitHubException error) {
            return ToolResult.error(error.getMessage());
        }
    }

    @AgentTool(name = TOOL_GITHUB_CREATE_BRANCH,
            description = "Create a fix branch from an existing branch or commit. Branch must start with ajentic/.",
            capability = Capability.MUTATE)
    public ToolResult createBranch(
            @ToolParam("Repository as owner/name, for example acme/checkout-service.") String repo,
            @ToolParam("New branch name; must start with ajentic/, for example ajentic/fix-1234.") String newBranch,
            @ToolParam("Branch or commit sha to branch from, for example main.") String fromRef) {
        if (!github.hasToken()) {
            return ToolResult.error("GitHub token not configured; set ajentic.github.token to create branches.");
        }
        try {
            github.createBranch(repo, newBranch, fromRef);
            return ToolResult.ok("Created branch " + newBranch + " from " + fromRef + " in " + repo + ".",
                    Map.of("branch", newBranch, "from", fromRef));
        } catch (GitHubException error) {
            return ToolResult.error(error.getMessage());
        }
    }

    @AgentTool(name = TOOL_GITHUB_COMMIT_FILE,
            description = "Create or update a file on a branch with a commit message. Pass existingSha to update.",
            capability = Capability.MUTATE)
    public ToolResult commitFile(
            @ToolParam("Repository as owner/name, for example acme/checkout-service.") String repo,
            @ToolParam("Branch to commit to, for example ajentic/fix-1234.") String branch,
            @ToolParam("File path to write, for example src/test/java/AppTest.java.") String path,
            @ToolParam("File content to commit.") String content,
            @ToolParam("Commit message.") String message,
            @ToolParam(value = "Existing blob sha when updating a file; omit to create.", required = false)
            String existingSha) {
        if (!github.hasToken()) {
            return ToolResult.error("GitHub token not configured; set ajentic.github.token to commit files.");
        }
        try {
            String sha = github.commitFile(repo, branch, path, content, message, existingSha);
            return ToolResult.ok((existingSha == null ? "Committed" : "Updated") + " " + path + " on " + branch
                    + " (" + sha + ").", Map.of("path", path, "sha", sha));
        } catch (GitHubException error) {
            return ToolResult.error(error.getMessage());
        }
    }

    @AgentTool(name = TOOL_GITHUB_CREATE_PULL_REQUEST,
            description = "Open a Pull Request from a fix branch onto a base branch. Returns an existing open PR.",
            capability = Capability.MUTATE)
    public ToolResult createPullRequest(
            @ToolParam("Repository as owner/name, for example acme/checkout-service.") String repo,
            @ToolParam("PR title.") String title,
            @ToolParam("PR body with root cause and diff summary.") String body,
            @ToolParam("Head branch holding the fix, for example ajentic/fix-1234.") String head,
            @ToolParam("Base branch to merge into, for example main.") String base) {
        if (!github.hasToken()) {
            return ToolResult.error("GitHub token not configured; set ajentic.github.token to open pull requests.");
        }
        try {
            GitHubClient.PullRequest pr = github.createPullRequest(repo, title, body, head, base);
            return ToolResult.ok("Opened pull request #" + pr.number() + " in " + repo + ": " + pr.url(),
                    Map.of("number", pr.number(), "url", pr.url()));
        } catch (GitHubException error) {
            return ToolResult.error(error.getMessage());
        }
    }

    @AgentTool(name = TOOL_GITHUB_TRIGGER_WORKFLOW,
            description = "Trigger a GitHub Actions workflow dispatch on a branch or ref.",
            capability = Capability.MUTATE)
    public ToolResult triggerWorkflow(
            @ToolParam("Repository as owner/name, for example acme/checkout-service.") String repo,
            @ToolParam("Workflow file name or ID, for example ci.yml or 123456.") String workflowId,
            @ToolParam(value = "Branch or ref to dispatch on; defaults to main.", required = false, defaultValue = "main")
            String ref) {
        if (!triggerWorkflowsEnabled) {
            return ToolResult.error("Workflow triggering is disabled; set ajentic.github.ci.trigger-workflows=true to enable.");
        }
        if (!github.hasToken()) {
            return ToolResult.error("GitHub token not configured; set ajentic.github.token to trigger workflows.");
        }
        try {
            github.triggerWorkflow(repo, workflowId, ref);
            return ToolResult.ok("Dispatched workflow " + workflowId + " on " + ref + " in " + repo + ".",
                    Map.of("repo", repo, "workflow", workflowId, "ref", ref));
        } catch (GitHubException error) {
            return ToolResult.error(error.getMessage());
        }
    }
}

