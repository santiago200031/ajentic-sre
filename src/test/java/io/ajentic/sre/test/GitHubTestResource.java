package io.ajentic.sre.test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * Runs the {@code @QuarkusTest} suite against WireMock over the real {@code GitHubClient} HTTP path, using the
 * structured stubs under {@code src/test/resources/wiremock}.
 */
public class GitHubTestResource implements QuarkusTestResourceLifecycleManager {

    private static WireMockServer server;

    @Override
    public Map<String, String> start() {
        synchronized (GitHubTestResource.class) {
            if (server == null || !server.isRunning()) {
                server = new WireMockServer(WireMockConfiguration.options()
                        .dynamicPort()
                        .usingFilesUnderClasspath("wiremock"));
                server.start();
            }
        }
        return Map.of(
                "ajentic.github.base-url", "http://localhost:" + server.port(),
                "ajentic.github.token", "gh_test_token");
    }

    @Override
    public void stop() {
        synchronized (GitHubTestResource.class) {
            if (server != null && server.isRunning()) {
                server.stop();
                server = null;
            }
        }
    }

    public static WireMockServer server() {
        return server;
    }
}
