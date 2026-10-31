package io.ajentic.sre.simulation;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Points {@code ajentic.scenarios.dir} at a temp dir holding one valid external pack. */
public class ScenarioPackDirTestResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        try {
            Path dir = Files.createTempDirectory("scenario-packs");
            Files.writeString(dir.resolve("db-pool-exhaustion-sample.yaml"), """
                    id: db-pool-exhaustion-sample
                    title: Database connection pool saturation (sample)
                    summary: Checkout latency climbs as the checkout API starves for connections.
                    incidentSummary: Saturation of the checkout database connection pool.
                    services:
                      - {name: edge-gateway, kind: GATEWAY, replicas: 3}
                      - {name: checkout-api, kind: API, replicas: 4}
                      - {name: samples-db, kind: DATABASE, replicas: 1}
                    faults:
                      - {kind: DB_POOL_EXHAUSTION, service: checkout-api, severity: 0.8}
                    runbooks: []
                    maxSteps: 12
                    """);
            return Map.of("ajentic.scenarios.dir", dir.toString());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public void stop() {
    }
}
