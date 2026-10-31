package io.ajentic.sre.agent.cloud.azure;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AzureTokenSourceTest {

    private static WireMockServer server;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
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
    void fetchesAndCachesTokenViaClientCredentials() {
        server.stubFor(post(urlEqualTo("/oauth2/token"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\": \"azure-test-token-123\", \"expires_in\": 3600}")));

        AzureTokenSource source = new AzureTokenSource(
                "tenant-1",
                "client-1",
                "secret-1",
                "https://management.azure.com/.default",
                "http://localhost:" + server.port() + "/oauth2/token",
                null,
                null);

        String token1 = source.getAccessToken();
        assertEquals("azure-test-token-123", token1);

        // Second call should return cached token without invoking HTTP again
        String token2 = source.getAccessToken();
        assertEquals("azure-test-token-123", token2);
        server.verify(1, WireMock.postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void fallsBackToImdsWhenCredentialsMissing() {
        server.stubFor(get(urlEqualTo("/metadata/token"))
                .withHeader("Metadata", equalTo("true"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\": \"imds-token-456\", \"expires_in\": 1800}")));

        AzureTokenSource source = new AzureTokenSource(
                null,
                null,
                null,
                null,
                null,
                "http://localhost:" + server.port() + "/metadata/token",
                null);

        String token = source.getAccessToken();
        assertEquals("imds-token-456", token);
        server.verify(1, WireMock.getRequestedFor(urlEqualTo("/metadata/token")));
    }

    @Test
    void invalidationForcesFreshFetch() {
        server.stubFor(post(urlEqualTo("/oauth2/token"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\": \"token-A\", \"expires_in\": 3600}")));

        AzureTokenSource source = new AzureTokenSource(
                "tenant-1", "client-1", "secret-1", null,
                "http://localhost:" + server.port() + "/oauth2/token", null, null);

        assertEquals("token-A", source.getAccessToken());

        source.invalidate();

        assertEquals("token-A", source.getAccessToken());
        server.verify(2, WireMock.postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void throwsWhenAccessTokenIsMissing() {
        server.stubFor(post(urlEqualTo("/oauth2/token"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"expires_in\": 3600}")));

        AzureTokenSource source = new AzureTokenSource(
                "tenant-1", "client-1", "secret-1", null,
                "http://localhost:" + server.port() + "/oauth2/token", null, null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, source::getAccessToken);
    }
}
