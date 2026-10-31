package io.ajentic.sre.simulation;

import io.ajentic.sre.simulation.ObservedSignal;
import io.ajentic.sre.simulation.ServiceHealth;
import io.ajentic.sre.simulation.TelemetrySignal;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.random.RandomGeneratorFactory;

final class SimulatedSystem {

    private static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");

    /** One simulated minute of incident time per step. */
    private static final Duration TICK = Duration.ofMinutes(1);

    /** Seeded config/flag/quota change templates so every service has inspectable history. */
    private static final String[][] CHANGE_TEMPLATES = {
            {DeployRecord.KIND_CONFIG, "adjusted rate limit", "- rate_limit_rps: 100\n+ rate_limit_rps: 120"},
            {DeployRecord.KIND_FEATURE_FLAG, "enabled feature flag search-caching",
                    "- search-caching: off\n+ search-caching: on"},
            {DeployRecord.KIND_QUOTA, "raised egress quota", "- egress_quota_gb: 100\n+ egress_quota_gb: 120"},
    };

    private final Scenario scenario;
    private final RandomGenerator random;
    private final Map<String, ServiceState> services = new LinkedHashMap<>();
    private final List<ActiveFault> faults = new ArrayList<>();
    private final List<DeployRecord> deploys = new ArrayList<>();

    private int step;
    private int wrongRemediations;
    private boolean catastrophic;
    private boolean diagnosed;

    SimulatedSystem(Scenario scenario, long seed) {
        this.scenario = scenario;
        this.random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        scenario.topology();
        scenario.services().forEach(spec -> services.put(spec.name(), new ServiceState(spec)));
        scenario.faults().forEach(fault -> faults.add(new ActiveFault(fault)));
        scenario.services().forEach(spec -> deploys.add(
                new DeployRecord(spec.name(), DeployRecord.KIND_DEPLOY, "v1.0.0", BASE_TIME.minus(Duration.ofDays(14)))));
        scenario.faults().stream()
                .filter(fault -> fault.kind() == FaultKind.BAD_DEPLOYMENT)
                .forEach(fault -> deploys.add(
                        new DeployRecord(fault.service(), DeployRecord.KIND_DEPLOY, "v2.1.0",
                                BASE_TIME.minus(Duration.ofMinutes(18)), "- v1.0.0\n+ v2.1.0")));
        scenario.services().forEach(spec -> {
            int count = random.nextInt(3);
            for (int i = 0; i < count; i++) {
                String[] template = CHANGE_TEMPLATES[random.nextInt(CHANGE_TEMPLATES.length)];
                Instant at = BASE_TIME
                        .minus(1 + random.nextInt(13), ChronoUnit.DAYS)
                        .minus(random.nextInt(720), ChronoUnit.MINUTES);
                deploys.add(new DeployRecord(spec.name(), template[0], template[1], at, template[2]));
            }
        });
        applyFaultEffects(1.0);
    }

    int step() {
        return step;
    }

    void tick() {
        step++;
        applyFaultEffects(0.5);
        if (allFaultsResolved()) {
            services.values().forEach(this::recover);
        }
        services.values().forEach(service ->
                service.setCpuUtilization(service.cpuUtilization() + random.nextGaussian() * 0.015));
    }

    List<ObservedSignal> query(TelemetrySignal signal, String serviceName) {
        ServiceState service = requireService(serviceName);
        return switch (signal) {
            case METRICS -> List.of(metrics(service));
            case LOGS -> logs(service);
            case TRACES -> traces(service);
            case DEPLOYS -> deploys(service);
        };
    }

    RemediationOutcome applyRemediation(io.ajentic.sre.simulation.action.RemediationKind remediation, String serviceName) {
        ServiceState service = requireService(serviceName);
        for (ActiveFault active : faults) {
            if (active.resolved() || !active.fault().service().equals(serviceName)) {
                continue;
            }
            if (active.fault().kind().requiredRemediation() == remediation) {
                active.resolve(step);
                return new RemediationOutcome(true, active.fault().kind().rootCause());
            }
        }
        wrongRemediations++;
        service.setErrorRate(service.errorRate() + 0.1);
        catastrophic = wrongRemediations >= 2;
        return new RemediationOutcome(false, null);
    }

    boolean correctDiagnosis(String serviceName, String rootCause) {
        return faults.stream()
                .filter(active -> active.fault().service().equals(serviceName))
                .anyMatch(active -> active.fault().kind().rootCause().equalsIgnoreCase(rootCause.trim()));
    }

    boolean allFaultsResolved() {
        return !faults.isEmpty() && faults.stream().allMatch(ActiveFault::resolved);
    }

    boolean catastrophic() {
        return catastrophic;
    }

    void markDiagnosed() {
        this.diagnosed = true;
    }

    boolean diagnosed() {
        return diagnosed;
    }

    Map<String, ServiceHealth> health() {
        Map<String, ServiceHealth> health = new LinkedHashMap<>();
        services.forEach((name, service) -> health.put(name, service.health()));
        return health;
    }

    String incidentSummary() {
        return scenario.incidentSummary();
    }

    /** Severity multiplier applied per hop when a fault degrades a dependent service. */
    private static final double CASCADE_FACTOR = 0.5;

    private void applyFaultEffects(double scale) {
        for (ActiveFault active : faults) {
            if (active.resolved()) {
                continue;
            }
            ServiceState service = requireService(active.fault().service());
            double severity = active.fault().severity() * scale;
            FaultKind kind = active.fault().kind();
            service.setErrorRate(service.errorRate() + kind.errorRateCoefficient() * severity);
            service.setP95LatencyMilliseconds(service.p95LatencyMilliseconds() + kind.latencyCoefficient() * severity);
            switch (kind) {
                case MEMORY_LEAK -> service.setMemoryUtilization(service.memoryUtilization() + 0.08 * severity);
                case DISK_PRESSURE -> service.setDiskUtilization(service.diskUtilization() + 0.32 * severity);
                default -> {
                }
            }
            cascade(active, service, severity);
        }
    }

    /**
     * A fault on a service degrades every service that depends on it, upstream through the topology DAG, with the
     * severity decayed by {@link #CASCADE_FACTOR} per edge. Only latency and error rate propagate: memory and disk
     * pressure are local to the faulty service.
     */
    private void cascade(ActiveFault active, ServiceState source, double severity) {
        List<String> ancestors = scenario.topology().ancestorsOf(source.name());
        for (int depth = 0; depth < ancestors.size(); depth++) {
            ServiceState ancestor = requireService(ancestors.get(depth));
            double decayed = severity * Math.pow(CASCADE_FACTOR, depth + 1);
            ancestor.setErrorRate(ancestor.errorRate() + active.fault().kind().errorRateCoefficient() * decayed);
            ancestor.setP95LatencyMilliseconds(
                    ancestor.p95LatencyMilliseconds() + active.fault().kind().latencyCoefficient() * decayed);
        }
    }

    private void recover(ServiceState service) {
        service.setErrorRate(Math.max(0.0, service.errorRate() - 0.3));
        service.setP95LatencyMilliseconds(Math.max(service.baseLatency(), service.p95LatencyMilliseconds() - 250));
        service.setMemoryUtilization(Math.max(0.45, service.memoryUtilization() - 0.15));
        service.setDiskUtilization(Math.max(0.4, service.diskUtilization() - 0.1));
    }

    private ObservedSignal metrics(ServiceState service) {
        return new ObservedSignal(
                TelemetrySignal.METRICS,
                service.name(),
                "cpu=%.0f%% memory=%.0f%% error_rate=%.2f p95_latency_ms=%.0f disk=%.0f%%".formatted(
                        service.cpuUtilization() * 100,
                        service.memoryUtilization() * 100,
                        service.errorRate(),
                        service.p95LatencyMilliseconds(),
                        service.diskUtilization() * 100),
                Map.of(
                        "cpuUtilization", round(service.cpuUtilization()),
                        "memoryUtilization", round(service.memoryUtilization()),
                        "errorRate", round(service.errorRate()),
                        "p95LatencyMs", Math.round(service.p95LatencyMilliseconds()),
                        "diskUtilization", round(service.diskUtilization())));
    }

    private List<ObservedSignal> logs(ServiceState service) {
        List<ObservedSignal> signals = new ArrayList<>();
        for (ActiveFault active : faults) {
            if (active.resolved() || !active.fault().service().equals(service.name())) {
                continue;
            }
            active.fault().kind().logLines().forEach(line -> signals.add(
                    new ObservedSignal(TelemetrySignal.LOGS, service.name(), line,
                            Map.of("level", LogLevel.ERROR.name()))));
        }
        if (signals.isEmpty()) {
            signals.add(new ObservedSignal(TelemetrySignal.LOGS, service.name(),
                    "GET /health 200 in 3ms", Map.of("level", LogLevel.INFO.name())));
        }
        return signals;
    }

    private List<ObservedSignal> traces(ServiceState service) {
        String status = switch (service.health()) {
            case OUTAGE -> "503";
            case DEGRADED -> "500";
            case HEALTHY -> "200";
        };
        return List.of(new ObservedSignal(
                TelemetrySignal.TRACES,
                service.name(),
                "GET /%s p95=%dms status=%s".formatted(service.name(), Math.round(service.p95LatencyMilliseconds()), status),
                Map.of(
                        "p95LatencyMs", Math.round(service.p95LatencyMilliseconds()),
                        "errorRate", round(service.errorRate()),
                        "status", status)));
    }

    private List<ObservedSignal> deploys(ServiceState service) {
        return recentChanges(service.name(), Integer.MAX_VALUE);
    }

    /** Recent deploys, config edits, flag flips and quota changes for one service, newest within the window. */
    List<ObservedSignal> recentChanges(String serviceName, int windowMinutes) {
        ServiceState service = requireService(serviceName);
        Instant cutoff = now().minus(windowMinutes, ChronoUnit.MINUTES);
        List<ObservedSignal> signals = new ArrayList<>();
        for (DeployRecord change : deploys) {
            if (!change.service().equals(service.name()) || change.occurredAt().isBefore(cutoff)) {
                continue;
            }
            signals.add(new ObservedSignal(
                    TelemetrySignal.DEPLOYS,
                    service.name(),
                    "%s %s at %s%s".formatted(
                            change.kind(),
                            change.summary(),
                            change.occurredAt(),
                            change.diff().isEmpty() ? "" : "\n" + change.diff().lines()
                                    .map(line -> "  " + line)
                                    .collect(Collectors.joining("\n"))),
                    Map.of(
                            "kind", change.kind(),
                            "summary", change.summary(),
                            "occurredAt", change.occurredAt().toString(),
                            "diff", change.diff())));
        }
        return signals;
    }

    /**
     * Ranks metric anomalies across the requested services plus their topology neighborhood within {@code window}
     * hops. Score is the absolute error-rate delta plus a tenth of the relative p95 latency growth over baseline,
     * so the faulted service ranks first and degraded dependents follow in decay order.
     */
    String correlateAnomalies(List<String> requested, int window) {
        Set<String> neighborhood = new LinkedHashSet<>();
        for (String name : requested.isEmpty() ? List.of(scenario.alertingService()) : requested) {
            requireService(name);
            neighborhood.add(name);
            List<String> frontier = List.of(name);
            for (int hop = 0; hop < window; hop++) {
                List<String> next = new ArrayList<>();
                for (String service : frontier) {
                    next.addAll(scenario.topology().dependenciesOf(service));
                }
                next.forEach(neighborhood::add);
                frontier = next;
                if (frontier.isEmpty()) {
                    break;
                }
            }
            scenario.topology().ancestorsOf(name).stream().limit(window).forEach(neighborhood::add);
        }
        List<String> ranked = neighborhood.stream()
                .sorted((a, b) -> Double.compare(anomalyScore(requireService(b)), anomalyScore(requireService(a))))
                .toList();
        return ranked.stream()
                .map(name -> {
                    ServiceState service = requireService(name);
                    return "%d. %s: error_rate=%.2f p95_latency_ms=%.0f (depends on: %s)".formatted(
                            ranked.indexOf(name) + 1,
                            name,
                            service.errorRate(),
                            service.p95LatencyMilliseconds(),
                            scenario.topology().dependenciesOf(name));
                })
                .collect(Collectors.joining("\n"));
    }

    private double anomalyScore(ServiceState service) {
        double latencyGrowth = Math.max(0.0, (service.p95LatencyMilliseconds() - service.baseLatency()) / service.baseLatency());
        return service.errorRate() + 0.1 * latencyGrowth;
    }

    Instant now() {
        return BASE_TIME.plus((long) step * TICK.toMinutes(), ChronoUnit.MINUTES);
    }

    private ServiceState requireService(String name) {
        ServiceState service = services.get(name);
        if (service == null) {
            throw new IllegalArgumentException("Unknown service '" + name + "' for scenario " + scenario.id());
        }
        return service;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
