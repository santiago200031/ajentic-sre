package io.ajentic.sre.agent.github;

/**
 * Raised when the GitHub REST API returns an error or a request fails. The message is safe to show the model: it never
 * contains the token or raw response headers.
 */
public class GitHubException extends RuntimeException {

    public GitHubException(String message) {
        super(message);
    }
}
