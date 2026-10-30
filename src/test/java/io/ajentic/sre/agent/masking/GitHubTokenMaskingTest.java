package io.ajentic.sre.agent.masking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code API_KEY} category redacts GitHub tokens (classic, fine-grained PAT, and app tokens) so tool
 * results that echo a token never reach the model.
 */
class GitHubTokenMaskingTest {

    @Test
    void masksClassicGithubToken() {
        MaskingSession session = new SensitiveDataMasker().newSession();
        String masked = session.mask("use token ghp_abcdefghijklmnopqrstuvwx to read the repo");
        assertFalse(masked.contains("ghp_abcdefghijklmnopqrstuvwx"), masked);
        assertTrue(masked.contains("[[API_KEY_1]]"), masked);
    }

    @Test
    void masksFineGrainedGithubToken() {
        MaskingSession session = new SensitiveDataMasker().newSession();
        String masked = session.mask("token github_pat_abcdefghijklmnopqrstuvwx is scoped");
        assertFalse(masked.contains("github_pat_abcdefghijklmnopqrstuvwx"), masked);
        assertTrue(masked.contains("[[API_KEY_1]]"), masked);
    }

    @Test
    void masksAppInstallationToken() {
        MaskingSession session = new SensitiveDataMasker().newSession();
        String masked = session.mask("token ghs_1234567890abcdefghijklmnopqrstuvwx is an app token");
        assertFalse(masked.contains("ghs_1234567890abcdefghijklmnopqrstuvwx"), masked);
        assertTrue(masked.contains("[[API_KEY_1]]"), masked);
    }
}
