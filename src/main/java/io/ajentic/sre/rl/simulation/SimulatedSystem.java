package io.ajentic.sre.rl.simulation;

import io.ajentic.sre.rl.ObservedSignal;
import io.ajentic.sre.rl.ServiceHealth;
import io.ajentic.sre.rl.TelemetrySignal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

final class SimulatedSystem {

    private static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");

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
        scenario.services().forEach(spec -> services.put(spec.name(), new ServiceState(spec)));
        scenario.faults().forEach(fault -> faults.add(new ActiveFault(fault)));
        scenario.services().forEach(spec -> deploys.add(
                new DeployRecord(spec.name(), "v1.0.0", BASE_TIME.minus(Duration.ofDays(14)))));
        scenario.faults().stream()
                .filter(fault -> fault.kind() == FaultKind.BAD_DEPLOYMENT)
                .forEach(fault -> deploys.add(
                        new DeployRecord(fault.service(), "v2.1.0", BASE_TIME.minus(Duration.ofMinutes(18)))));
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

    RemediationOutcome applyRemediation(io.ajentic.sre.rl.action.RemediationKind remediation, String serviceName) {
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

    private void applyFaultEffects(double scale) {
        for (ActiveFault active : faults) {
            if (active.resolved()) {
                continue;
            }
            ServiceState service = requireService(active.fault().service());
            double severity = active.fault().severity() * scale;
            switch (active.fault().kind()) {
                case DB_POOL_EXHAUSTION -> {
                    service.setErrorRate(service.errorRate() + 0.06 * severity);
                    service.setP95LatencyMilliseconds(service.p95LatencyMilliseconds() + 70 * severity);
                }
                case MEMORY_LEAK -> {
                    service.setMemoryUtilization(service.memoryUtilization() + 0.08 * severity);
                    service.setErrorRate(service.errorRate() + 0.03 * severity);
                }
                case CERTIFICATE_EXPIRY -> service.setErrorRate(service.errorRate() + 0.14 * severity);
                case DNS_FAILURE -> {
                    service.setErrorRate(service.errorRate() + 0.16 * severity);
                    service.setP95LatencyMilliseconds(service.p95LatencyMilliseconds() + 130 * severity);
                }
                case BAD_DEPLOYMENT -> {
                    service.setErrorRate(service.errorRate() + 0.14 * severity);
                    service.setP95LatencyMilliseconds(service.p95LatencyMilliseconds() + 90 * severity);
                }
                case DISK_PRESSURE -> {
                    service.setDiskUtilization(service.diskUtilization() + 0.32 * severity);
                    service.setErrorRate(service.errorRate() + 0.05 * severity);
                }
            }
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
        List<ObservedSignal> signals = new ArrayList<>();
        for (DeployRecord deploy : deploys) {
            if (!deploy.service().equals(service.name())) {
                continue;
            }
            signals.add(new ObservedSignal(
                    TelemetrySignal.DEPLOYS,
                    service.name(),
                    "deployed %s at %s".formatted(deploy.version(), deploy.deployedAt()),
                    Map.of("version", deploy.version(), "deployedAt", deploy.deployedAt().toString())));
        }
        return signals;
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
