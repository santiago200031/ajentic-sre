package io.ajentic.sre.connectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles HMAC-SHA256 request signing and verification for the sidecar connectors contract.
 * Includes timestamp verification (5-minute window) and nonce anti-replay protection.
 */
public final class SidecarSecurity {

    private static final String HMAC_SHA256 = "HmacSHA256";
    public static final Duration TIMESTAMP_WINDOW = Duration.ofMinutes(5);

    private final String sharedSecret;
    private final Map<String, Long> seenNonces = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(1000, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > 5000;
                }
            });

    public SidecarSecurity(String sharedSecret) {
        this.sharedSecret = (sharedSecret != null && !sharedSecret.isBlank()) ? sharedSecret : "default-sidecar-secret";
    }

    public String sign(String timestamp, String nonce, String body) {
        String data = timestamp + ":" + nonce + ":" + (body != null ? body : "");
        return computeHmacSha256(sharedSecret, data);
    }

    public boolean verify(String timestampStr, String nonce, String signature, String body) {
        if (timestampStr == null || nonce == null || signature == null) {
            return false;
        }

        Instant timestamp;
        try {
            timestamp = Instant.parse(timestampStr);
        } catch (Exception e) {
            return false;
        }

        Instant now = Instant.now();
        if (Math.abs(Duration.between(timestamp, now).toSeconds()) > TIMESTAMP_WINDOW.toSeconds()) {
            return false; // Stale timestamp outside 5-minute window
        }

        // Check nonce replay
        long nowMs = now.toEpochMilli();
        Long prev = seenNonces.putIfAbsent(nonce, nowMs);
        if (prev != null) {
            return false; // Nonce already seen -> replay attempt
        }

        // Verify HMAC
        String expectedSignature = sign(timestampStr, nonce, body);
        return MessageDigestUtil.constantTimeEquals(expectedSignature, signature);
    }

    public static String computeHmacSha256(String key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed computing HMAC-SHA256", e);
        }
    }

    private static final class MessageDigestUtil {
        public static boolean constantTimeEquals(String a, String b) {
            if (a == null || b == null) {
                return false;
            }
            byte[] ab = a.getBytes(StandardCharsets.UTF_8);
            byte[] bb = b.getBytes(StandardCharsets.UTF_8);
            if (ab.length != bb.length) {
                return false;
            }
            int result = 0;
            for (int i = 0; i < ab.length; i++) {
                result |= ab[i] ^ bb[i];
            }
            return result == 0;
        }
    }
}
