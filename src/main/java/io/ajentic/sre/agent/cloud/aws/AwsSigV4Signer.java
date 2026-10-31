package io.ajentic.sre.agent.cloud.aws;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Standard AWS Signature Version 4 signer implemented purely with JDK crypto primitives.
 * Produces signed authorization headers and x-amz-date headers without requiring the AWS SDK.
 */
public final class AwsSigV4Signer {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SCHEME = "AWS4-HMAC-SHA256";
    private static final String TERMINATOR = "aws4_request";
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsSigV4Signer() {
    }

    /**
     * Signs an HTTP request and returns the headers to attach (including Authorization, x-amz-date, and host).
     */
    public static Map<String, String> sign(
            String httpMethod,
            URI uri,
            Map<String, String> existingHeaders,
            byte[] payload,
            String service,
            String region,
            String accessKeyId,
            String secretAccessKey,
            String sessionToken,
            Instant timestamp) {

        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(accessKeyId, "accessKeyId");
        Objects.requireNonNull(secretAccessKey, "secretAccessKey");
        Instant time = (timestamp != null) ? timestamp : Instant.now();

        String amzDate = DATE_TIME_FORMATTER.format(time);
        String dateStamp = DATE_FORMATTER.format(time);

        // Normalize headers
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (existingHeaders != null) {
            headers.putAll(existingHeaders);
        }
        headers.put("host", uri.getHost() + (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443 ? ":" + uri.getPort() : ""));
        headers.put("x-amz-date", amzDate);
        if (sessionToken != null && !sessionToken.isBlank()) {
            headers.put("x-amz-security-token", sessionToken);
        }

        // Canonical URI
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        // Canonical Query String
        String canonicalQueryString = buildCanonicalQueryString(uri.getRawQuery());

        // Canonical Headers and Signed Headers
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String lowerKey = entry.getKey().toLowerCase(Locale.ROOT);
            if (!signedHeaders.isEmpty()) {
                signedHeaders.append(";");
            }
            signedHeaders.append(lowerKey);
            canonicalHeaders.append(lowerKey).append(":").append(entry.getValue().trim().replaceAll("\\s+", " ")).append("\n");
        }

        // Hashed Payload
        String payloadHash = hexSha256(payload != null ? payload : new byte[0]);

        // Canonical Request
        String canonicalRequest = httpMethod.toUpperCase(Locale.ROOT) + "\n"
                + path + "\n"
                + canonicalQueryString + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        // Credential Scope
        String credentialScope = dateStamp + "/" + region + "/" + service + "/" + TERMINATOR;

        // String to Sign
        String stringToSign = SCHEME + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + hexSha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        // Calculate Signature
        byte[] signingKey = getSignatureKey(secretAccessKey, dateStamp, region, service);
        String signature = HexFormat.of().formatHex(hmacSha256(signingKey, stringToSign));

        // Authorization Header
        String authorizationHeader = SCHEME + " "
                + "Credential=" + accessKeyId + "/" + credentialScope + ", "
                + "SignedHeaders=" + signedHeaders + ", "
                + "Signature=" + signature;

        Map<String, String> result = new LinkedHashMap<>(headers);
        result.put("Authorization", authorizationHeader);
        return result;
    }

    private static String buildCanonicalQueryString(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        String[] pairs = rawQuery.split("&");
        List<String> sortedPairs = new ArrayList<>();
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = urlEncode(pair.substring(0, idx));
                String value = urlEncode(pair.substring(idx + 1));
                sortedPairs.add(key + "=" + value);
            } else {
                sortedPairs.add(urlEncode(pair) + "=");
            }
        }
        Collections.sort(sortedPairs);
        return String.join("&", sortedPairs);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    public static String hexSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 failure", e);
        }
    }

    private static byte[] getSignatureKey(String key, String dateStamp, String regionName, String serviceName) {
        byte[] kSecret = ("AWS4" + key).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, dateStamp);
        byte[] kRegion = hmacSha256(kDate, regionName);
        byte[] kService = hmacSha256(kRegion, serviceName);
        return hmacSha256(kService, TERMINATOR);
    }
}
