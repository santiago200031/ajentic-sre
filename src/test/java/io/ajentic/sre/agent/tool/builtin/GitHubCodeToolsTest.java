package io.ajentic.sre.agent.tool.builtin;

import io.ajentic.sre.agent.github.GitHubClient;
import io.ajentic.sre.agent.github.GitHubException;
import io.ajentic.sre.agent.tool.ToolResult;
import io.ajentic.sre.test.GitHubTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GitHubClient} and {@link GitHubCodeTools} over the real HTTP path against WireMock, mirroring the
 * {@code ApiEndToEndTest} approach for the LLM client.
 */
@QuarkusTest
@WithTestResource(GitHubTestResource.class)
class GitHubCodeToolsTest {

    @Inject
    GitHubClient github;

    @Inject
    GitHubCodeTools tools;

    @Test
    void listsFilesOverHttp() {
        List<String> files = github.listFiles("acme/checkout-service", "main", null);
        assertEquals(3, files.size());
        assertTrue(files.contains("src/main/java/App.java"));
        assertTrue(files.contains("src/test/java/AppTest.java"));
    }

    @Test
    void readsFileContentDecoded() {
        GitHubClient.FileContent content = github.readFile("acme/checkout-service", "src/main/java/App.java", "main")
                .orElseThrow();
        assertTrue(content.content().contains("hello"));
        assertEquals("blob1", content.sha());
    }

    @Test
    void reportsCiStatusFromCheckRuns() {
        GitHubClient.CiStatus status = github.getCiStatus("acme/checkout-service", "def456");
        assertEquals(2, status.totalCount());
        assertTrue(status.runs().stream()
                .anyMatch(run -> "build".equals(run.name()) && "success".equals(run.conclusion())));
        assertTrue(status.runs().stream()
                .anyMatch(run -> "test".equals(run.name()) && "failure".equals(run.conclusion())));
    }

    @Test
    void createsBranchCommitsAndOpensPullRequest() {
        github.createBranch("acme/checkout-service", "ajentic/fix-1234", "main");
        String commitSha = github.commitFile("acme/checkout-service", "ajentic/fix-1234",
                "src/test/java/AppTest.java", "new content", "add regression test", null);
        assertEquals("commit789", commitSha);
        GitHubClient.PullRequest pr = github.createPullRequest("acme/checkout-service", "Fix", "Body",
                "ajentic/fix-1234", "main");
        assertEquals(42, pr.number());
        assertEquals("https://github.com/acme/checkout-service/pull/42", pr.url());
    }

    @Test
    void toolsReturnOkResultsToTheAgent() {
        ToolResult result = tools.listFiles("acme/checkout-service", "main", null);
        assertTrue(result.success());
        assertTrue(result.content().contains("src/main/java/App.java"));
    }

    @Test
    void rejectsRepositoryWithoutOwnerSlashName() {
        assertThrows(GitHubException.class, () -> github.listFiles("bad-repo-without-slash", "main", null));
    }

    @Test
    void rejectsPathTraversal() {
        assertThrows(GitHubException.class,
                () -> github.readFile("acme/checkout-service", "../secret", "main"));
    }

    @Test
    void rejectsNonAjenticBranch() {
        assertThrows(GitHubException.class,
                () -> github.createBranch("acme/checkout-service", "feature/x", "main"));
    }

    @Test
    void readsMultilineFileContentDecoded() {
        GitHubClient.FileContent content = github.readFile("acme/checkout-service", "src/main/java/MultilineApp.java", "main")
                .orElseThrow();
        assertTrue(content.content().contains("multiline"));
        assertEquals("blob-multi", content.sha());
    }

    @Test
    void listsFilesWithPrefix() {
        List<String> files = github.listFiles("acme/checkout-service", "main", "src/");
        assertEquals(2, files.size());
        assertTrue(files.contains("src/main/java/App.java"));
        assertTrue(files.contains("src/test/java/AppTest.java"));
        assertFalse(files.contains("README.md"));
    }

    @Test
    void toleratesExistingBranch() {
        // WireMock returns 422 "Reference already exists" for ajentic/existing-branch; should not throw
        github.createBranch("acme/checkout-service", "ajentic/existing-branch", "main");
    }

    @Test
    void reusesExistingOpenPullRequest() {
        // WireMock returns 422 for creating PR on ajentic/existing-pr, then lists open PRs returning #99
        GitHubClient.PullRequest pr = github.createPullRequest("acme/checkout-service", "Fix", "Body",
                "ajentic/existing-pr", "main");
        assertEquals(99, pr.number());
        assertEquals("https://github.com/acme/checkout-service/pull/99", pr.url());
    }

    @Test
    void commitsFileUpdateWithExistingSha() {
        String sha = github.commitFile("acme/checkout-service", "ajentic/fix-1234",
                "src/test/java/AppTest.java", "updated content", "update test", "blob2");
        assertEquals("commit789", sha);
    }

    @Test
    void reportsMissingTokenForMutations() {
        GitHubClient noTokenClient = new GitHubClient(new ObjectMapper(),
                "http://localhost:" + GitHubTestResource.server().port(), "", Duration.ofSeconds(30));
        assertFalse(noTokenClient.hasToken());

        GitHubCodeTools unauthedTools = new GitHubCodeTools(noTokenClient);
        ToolResult branchRes = unauthedTools.createBranch("acme/checkout-service", "ajentic/test", "main");
        assertFalse(branchRes.success());
        assertTrue(branchRes.content().contains("token not configured"));

        ToolResult commitRes = unauthedTools.commitFile("acme/checkout-service", "ajentic/test", "f.txt", "c", "m", null);
        assertFalse(commitRes.success());
        assertTrue(commitRes.content().contains("token not configured"));

        ToolResult prRes = unauthedTools.createPullRequest("acme/checkout-service", "t", "b", "ajentic/test", "main");
        assertFalse(prRes.success());
        assertTrue(prRes.content().contains("token not configured"));
    }

    @Test
    void mutationGateBlocksMutatingToolsWhenDisabled() {
        io.ajentic.sre.agent.tool.DefaultToolAuthorization readOnlyAuth =
                new io.ajentic.sre.agent.tool.DefaultToolAuthorization(java.util.Optional.empty(), false);

        io.ajentic.sre.agent.tool.ToolDescriptor readTool =
                new io.ajentic.sre.agent.tool.ToolDescriptor("github_list_files", "desc", Map.of(), io.ajentic.sre.agent.tool.Capability.READ);
        io.ajentic.sre.agent.tool.ToolDescriptor mutateTool =
                new io.ajentic.sre.agent.tool.ToolDescriptor("github_commit_file", "desc", Map.of(), io.ajentic.sre.agent.tool.Capability.MUTATE);

        assertTrue(readOnlyAuth.isAuthorized(readTool));
        assertFalse(readOnlyAuth.isAuthorized(mutateTool));
    }
}
