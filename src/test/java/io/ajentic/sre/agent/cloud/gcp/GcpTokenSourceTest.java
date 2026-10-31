package io.ajentic.sre.agent.cloud.gcp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GcpTokenSourceTest {

    private static WireMockServer server;
    private static String privateKeyPem;

    @BeforeAll
    static void startServer() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    @Test
    void fetchesAndCachesTokenViaJwtAssertion() {
        server.stubFor(post(urlEqualTo("/oauth2/token"))
                .withRequestBody(containing("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\": \"gcp-jwt-token-123\", \"expires_in\": 3600}")));

        GcpTokenSource source = new GcpTokenSource(
                "service-acc@proj.iam.gserviceaccount.com",
                privateKeyPem,
                null,
                "http://localhost:" + server.port() + "/oauth2/token",
                null,
                null);

        String token1 = source.getAccessToken();
        assertEquals("gcp-jwt-token-123", token1);

        // Cached token reused
        String token2 = source.getAccessToken();
        assertEquals("gcp-jwt-token-123", token2);
        server.verify(1, WireMock.postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void fallsBackToMetadataServerWhenNoCredentials() {
        server.stubFor(get(urlEqualTo("/computeMetadata/v1/token"))
                .withHeader("Metadata-Flavor", equalTo("Google"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\": \"gcp-meta-token-789\", \"expires_in\": 3600}")));

        GcpTokenSource source = new GcpTokenSource(
                null,
                null,
                null,
                null,
                "http://localhost:" + server.port() + "/computeMetadata/v1/token",
                null);

        String token = source.getAccessToken();
        assertEquals("gcp-meta-token-789", token);
        server.verify(1, WireMock.getRequestedFor(urlEqualTo("/computeMetadata/v1/token")));
    }

    @Test
    void throwsWhenAccessTokenIsMissing() {
        server.stubFor(get(urlEqualTo("/computeMetadata/v1/token"))
                .withHeader("Metadata-Flavor", equalTo("Google"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"expires_in\": 3600}")));

        GcpTokenSource source = new GcpTokenSource(
                null,
                null,
                null,
                null,
                "http://localhost:" + server.port() + "/computeMetadata/v1/token",
                null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, source::getAccessToken);
    }
}
