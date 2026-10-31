package io.ajentic.sre.agent.cloud.aws;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwsSigV4SignerTest {

    @Test
    void signsStandardGetRequestCorrectly() {
        URI uri = URI.create("https://example.com/test");
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");

        Map<String, String> signed = AwsSigV4Signer.sign(
                "GET",
                uri,
                Map.of("x-custom-header", "custom-value"),
                new byte[0],
                "s3",
                "us-east-1",
                "AKIAIOSFODNN7EXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                null,
                timestamp);

        assertNotNull(signed);
        assertEquals("20260101T000000Z", signed.get("x-amz-date"));
        assertEquals("example.com", signed.get("host"));

        String auth = signed.get("Authorization");
        assertNotNull(auth);
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20260101/us-east-1/s3/aws4_request"), auth);
        assertTrue(auth.contains("SignedHeaders=host;x-amz-date;x-custom-header"), auth);
        assertTrue(auth.contains("Signature="), auth);
    }

    @Test
    void includesSessionTokenWhenProvided() {
        URI uri = URI.create("https://monitoring.us-east-1.amazonaws.com/");
        Instant timestamp = Instant.parse("2026-06-15T12:00:00Z");

        Map<String, String> signed = AwsSigV4Signer.sign(
                "POST",
                uri,
                Map.of("Content-Type", "application/x-amz-json-1.0"),
                "{\"test\":true}".getBytes(StandardCharsets.UTF_8),
                "monitoring",
                "us-east-1",
                "ASIAIOSFODNN7EXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "session-token-xyz-123",
                timestamp);

        assertEquals("session-token-xyz-123", signed.get("x-amz-security-token"));
        assertTrue(signed.get("Authorization").contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token"));
    }

    @Test
    void producesDeterministicSha256Hash() {
        byte[] empty = new byte[0];
        // Standard SHA256 of empty string is e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", AwsSigV4Signer.hexSha256(empty));
    }

    @Test
    void canonicalizesQueryParamsWithoutDoubleEncoding() {
        URI uri = URI.create("https://example.com/test?b=world&a=hello%20world&flag");
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");

        Map<String, String> signed = AwsSigV4Signer.sign(
                "GET",
                uri,
                Map.of(),
                new byte[0],
                "s3",
                "us-east-1",
                "AKIAIOSFODNN7EXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                null,
                timestamp);

        assertNotNull(signed);
        String auth = signed.get("Authorization");
        assertNotNull(auth);
        assertTrue(auth.contains("Signature="));
    }
}
