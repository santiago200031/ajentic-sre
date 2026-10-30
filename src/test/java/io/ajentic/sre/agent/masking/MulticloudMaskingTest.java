package io.ajentic.sre.agent.masking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-category round-trip, placeholder stability, and negative (no over-masking) checks for the multi-cloud
 * categories: GCP resource paths, Azure resource ids, Kubernetes name+uid pairs, Bearer tokens, and AWS access keys.
 */
class MulticloudMaskingTest {

    private final MaskingSession session = new SensitiveDataMasker().newSession();

    @Test
    void masksGcpResourcePathWholeAndRoundTrips() {
        String original = "//storage.googleapis.com/projects/billing-proj-123456789/objects/app.log";
        String input = "data landed in " + original;
        String masked = session.mask(input);
        assertFalse(masked.contains(original), masked);
        assertTrue(masked.contains("[[GCP_1]]"), masked);
        assertEquals(input, session.unmask(masked));
    }

    @Test
    void masksAzureResourceIdBeforeTheGuidCouldBeReadAsPlainText() {
        String original = "/subscriptions/550e8400-e29b-41d4-a716-446655440000/resourceGroups/prod-rg/website";
        String input = "host moved to " + original;
        String masked = session.mask(input);
        assertFalse(masked.contains("550e8400-e29b-41d4-a716-446655440000"), masked);
        assertTrue(masked.contains("[[AZURE_1]]"), masked);
        assertEquals(input, session.unmask(masked));
    }

    @Test
    void masksK8sNameWithUidButNotAStandaloneUuid() {
        String pair = "db-creds:550e8400-e29b-41d4-a716-446655440000";
        String masked = session.mask("secret " + pair + " rotated");
        assertFalse(masked.contains(pair), masked);
        assertTrue(masked.contains("[[K8S_1]]"), masked);
        String bareUuid = "id 550e8400-e29b-41d4-a716-446655440000";
        assertEquals(bareUuid, session.mask(bareUuid));
    }

    @Test
    void masksBearerTokensButNotShortOnes() {
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzcmUifQ.c2ln";
        String masked = session.mask("Authorization: Bearer " + token);
        assertFalse(masked.contains(token), masked);
        assertTrue(masked.contains("[[BEARER_1]]"), masked);
        assertEquals("Bearer abc", session.mask("Bearer abc"));
    }

    @Test
    void masksAwsAccessKeyId() {
        String masked = session.mask("using AKIAIOSFODNN7EXAMPLE for signing");
        assertFalse(masked.contains("AKIAIOSFODNN7EXAMPLE"), masked);
        assertTrue(masked.contains("[[API_KEY_1]]"), masked);
    }

    @Test
    void bearerPrefixedApiTokensStayInTheApiKeyCategory() {
        String token = "ghp_" + "abcdefghijklmnopqrstuvwx";
        String masked = session.mask("Authorization: Bearer " + token);
        assertFalse(masked.contains(token), masked);
        assertTrue(masked.contains("[[API_KEY_1]]"), masked);
        assertFalse(masked.contains("[[BEARER_1]]"), masked);
    }

    @Test
    void placeholdersAreStablePerOriginalAndDistinctAcrossOriginals() {
        String first = session.mask("arn:aws:logs:us-east-1:123456789012:/log-group/checkout:stream/one");
        String second = session.mask("arn:aws:logs:us-east-1:123456789012:/log-group/checkout:stream/two");
        assertTrue(first.contains("[[ARN_1]]"), first);
        assertTrue(second.contains("[[ARN_2]]"), second);
        assertEquals(first, session.mask("arn:aws:logs:us-east-1:123456789012:/log-group/checkout:stream/one"));
    }

    @Test
    void plainPathsAndUuidsAreNotOverMasked() {
        assertEquals("/var/log/app.log", session.mask("/var/log/app.log"));
        assertEquals("ref 550e8400-e29b-41d4-a716-446655440000", session.mask("ref 550e8400-e29b-41d4-a716-446655440000"));
        assertEquals("Bearer", session.mask("Bearer"));
    }
}
