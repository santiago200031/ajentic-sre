package io.ajentic.sre.rl.simulation;

import io.ajentic.sre.rl.simulation.pack.ScenarioPack;
import io.quarkus.arc.DefaultBean;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.TreeSet;
import java.util.stream.Stream;

@DefaultBean
@ApplicationScoped
public class ScenarioLibrary implements ScenarioRepository {

    private static final List<String> EXTENSIONS = List.of("yaml", "yml", "json");

    private final SequencedMap<String, Scenario> scenarios;
    private final List<Scenario> ordered;

    @Inject
    public ScenarioLibrary(
            @ConfigProperty(name = "ajentic.scenarios.dir", defaultValue = "") Optional<String> scenariosDir,
            Provider<ObjectMapper> json) {
        LinkedHashMap<String, Scenario> index = new LinkedHashMap<>();
        build().forEach(scenario -> index.put(scenario.id(), scenario));
        loadPacks(Path.of(scenariosDir.orElse("")), json.get()).forEach(scenario -> index.putIfAbsent(scenario.id(), scenario));
        this.scenarios = Collections.unmodifiableSequencedMap(index);
        this.ordered = List.copyOf(index.values());
    }

    /** Test/non-CDI convenience: built-ins only, external packs disabled. */
    public ScenarioLibrary() {
        this(Optional.of(""), () -> new tools.jackson.databind.ObjectMapper());
    }

    /** Test seam: built-ins plus packs from {@code dir}, throwing on any invalid pack. */
    public List<Scenario> loadForTest(Path dir) {
        LinkedHashMap<String, Scenario> index = new LinkedHashMap<>();
        build().forEach(scenario -> index.put(scenario.id(), scenario));
        loadPacks(dir, new tools.jackson.databind.ObjectMapper()).forEach(scenario -> index.putIfAbsent(scenario.id(), scenario));
        return new ArrayList<>(index.values());
    }

    @Override
    public List<Scenario> all() {
        return ordered;
    }

    @Override
    @CacheResult(cacheName = "scenario-library")
    public Optional<Scenario> byId(String id) {
        return Optional.ofNullable(scenarios.get(id));
    }

    private static List<Scenario> build() {
        return List.of(
                new Scenario(
                        "checkout-latency-db-pool",
                        "Checkout latency from a saturated database pool",
                        "A latency regression on the checkout path caused by database connection starvation.",
                        FaultKind.DB_POOL_EXHAUSTION.incidentSummary(),
                        List.of(
                                new ServiceSpec("edge-gateway", ServiceKind.GATEWAY, 3),
                                new ServiceSpec("checkout-api", ServiceKind.API, 4),
                                new ServiceSpec("orders-db", ServiceKind.DATABASE, 1)),
                        List.of(new Fault(FaultKind.DB_POOL_EXHAUSTION, "checkout-api", 0.8)),
                        List.of(new Runbook("rb-db-pool", "Database connection pool saturation",
                                "Confirm pool saturation in the metrics, then scale out checkout-api or raise the pool limit. "
                                        + "Verify that connections return to the baseline after the change.")),
                        12),

                new Scenario(
                        "payments-memory-leak",
                        "Payments worker heap exhaustion",
                        "The payments worker leaks memory until the liveness probe restarts it.",
                        FaultKind.MEMORY_LEAK.incidentSummary(),
                        List.of(
                                new ServiceSpec("edge-gateway", ServiceKind.GATEWAY, 3),
                                new ServiceSpec("payments-api", ServiceKind.API, 3),
                                new ServiceSpec("payments-worker", ServiceKind.WORKER, 2),
                                new ServiceSpec("ledger-db", ServiceKind.DATABASE, 1)),
                        List.of(new Fault(FaultKind.MEMORY_LEAK, "payments-worker", 0.75)),
                        List.of(new Runbook("rb-memory", "Heap exhaustion from a memory leak",
                                "Correlate heap growth with the last release. Restart the worker to restore service, "
                                        + "then follow up with the owning team to fix the leak.")),
                        12),

                new Scenario(
                        "auth-certificate-expiry",
                        "Expired certificate on the auth service",
                        "Auth traffic fails TLS verification after the serving certificate expired.",
                        FaultKind.CERTIFICATE_EXPIRY.incidentSummary(),
                        List.of(
                                new ServiceSpec("edge-gateway", ServiceKind.GATEWAY, 3),
                                new ServiceSpec("auth-api", ServiceKind.API, 3),
                                new ServiceSpec("identity-db", ServiceKind.DATABASE, 1)),
                        List.of(new Fault(FaultKind.CERTIFICATE_EXPIRY, "auth-api", 0.9)),
                        List.of(new Runbook("rb-cert", "Expired serving certificate",
                                "Verify the expiry timestamp, install the renewed certificate through the configuration "
                                        + "pipeline, and confirm the handshake succeeds.")),
                        12),

                new Scenario(
                        "search-dns-failure",
                        "Search service DNS resolution failure",
                        "Cluster DNS stops resolving internal service names, breaking search fan-out.",
                        FaultKind.DNS_FAILURE.incidentSummary(),
                        List.of(
                                new ServiceSpec("edge-gateway", ServiceKind.GATEWAY, 3),
                                new ServiceSpec("search-api", ServiceKind.API, 3),
                                new ServiceSpec("search-index", ServiceKind.CACHE, 2)),
                        List.of(new Fault(FaultKind.DNS_FAILURE, "search-api", 0.85)),
                        List.of(new Runbook("rb-dns", "Cluster DNS resolution failure",
                                "Confirm SERVFAIL or NXDOMAIN responses, then fail over to the secondary resolver and "
                                        + "drain the unhealthy resolver path.")),
                        12),

                new Scenario(
                        "orders-bad-deployment",
                        "Orders error spike after a deployment",
                        "A recent orders-api deployment introduced a regression that raises 5xx responses.",
                        FaultKind.BAD_DEPLOYMENT.incidentSummary(),
                        List.of(
                                new ServiceSpec("edge-gateway", ServiceKind.GATEWAY, 3),
                                new ServiceSpec("orders-api", ServiceKind.API, 4),
                                new ServiceSpec("orders-db", ServiceKind.DATABASE, 1)),
                        List.of(new Fault(FaultKind.BAD_DEPLOYMENT, "orders-api", 0.8)),
                        List.of(new Runbook("rb-rollback", "Regression after deployment",
                                "Correlate the error spike with the deploy timestamp. Roll back to the previous "
                                        + "version and confirm the error rate recovers.")),
                        12),

                new Scenario(
                        "storage-disk-pressure",
                        "Storage worker disk pressure",
                        "The storage worker node is evicting pods because its volume is nearly full.",
                        FaultKind.DISK_PRESSURE.incidentSummary(),
                        List.of(
                                new ServiceSpec("edge-gateway", ServiceKind.GATEWAY, 3),
                                new ServiceSpec("storage-worker", ServiceKind.WORKER, 2),
                                new ServiceSpec("object-store", ServiceKind.DATABASE, 1)),
                        List.of(new Fault(FaultKind.DISK_PRESSURE, "storage-worker", 0.85)),
                        List.of(new Runbook("rb-disk", "Node disk pressure",
                                "Confirm the volume utilization, expand the persistent volume, and verify that "
                                        + "evictions stop.")),
                        12));
    }

    /** Loads packs from {@code dir} (empty or absent disables loading); built-ins win on id conflict. */
    private List<Scenario> loadPacks(Path dir, ObjectMapper json) {
        if (dir == null || dir.toString().isBlank() || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Scenario> packs = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(ScenarioLibrary::isPack).forEach(file -> packs.add(parse(file, json)));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read scenario packs from " + dir + ": " + exception.getMessage(), exception);
        }
        TreeSet<String> seen = new TreeSet<>();
        packs.forEach(scenario -> {
            if (!seen.add(scenario.id())) {
                throw new IllegalStateException("Duplicate scenario id '" + scenario.id() + "' in " + dir);
            }
        });
        return new ArrayList<>(packs);
    }

    private static boolean isPack(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static Scenario parse(Path file, ObjectMapper json) {
        ObjectMapper mapper = isYaml(file) ? new YAMLMapper() : json;
        try {
            ScenarioPack pack = mapper.readValue(file.toFile(), ScenarioPack.class);
            validate(file, pack);
            return pack.toScenario();
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Invalid scenario pack " + file + ": " + exception.getMessage(), exception);
        }
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private static void validate(Path file, ScenarioPack pack) {
        if (pack.id() == null || pack.id().isBlank()) {
            throw new IllegalStateException("Scenario pack " + file + " is missing an id");
        }
        if (pack.services() == null || pack.services().isEmpty()) {
            throw new IllegalStateException("Scenario pack '" + pack.id() + "' from " + file + " has no services");
        }
        TreeSet<String> names = new TreeSet<>();
        pack.services().forEach(service -> names.add(service.name()));
        for (ScenarioPack.FaultPack fault : pack.faults()) {
            if (!names.contains(fault.service())) {
                throw new IllegalStateException("Scenario pack '" + pack.id() + "' from " + file
                        + " references unknown service '" + fault.service() + "'");
            }
        }
    }
}
