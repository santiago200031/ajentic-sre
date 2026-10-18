package io.ajentic.sre.rl.simulation;

import io.ajentic.sre.rl.ServiceHealth;

final class ServiceState {

    private final ServiceSpec spec;
    private double cpuUtilization;
    private double memoryUtilization;
    private double errorRate;
    private double p95LatencyMilliseconds;
    private double diskUtilization;

    ServiceState(ServiceSpec spec) {
        this.spec = spec;
        this.cpuUtilization = 0.35;
        this.memoryUtilization = 0.45;
        this.errorRate = 0.0;
        this.p95LatencyMilliseconds = baseLatency();
        this.diskUtilization = 0.4;
    }

    String name() {
        return spec.name();
    }

    ServiceKind kind() {
        return spec.kind();
    }

    int replicas() {
        return spec.replicas();
    }

    double cpuUtilization() {
        return cpuUtilization;
    }

    void setCpuUtilization(double value) {
        this.cpuUtilization = clamp(value);
    }

    double memoryUtilization() {
        return memoryUtilization;
    }

    void setMemoryUtilization(double value) {
        this.memoryUtilization = clamp(value);
    }

    double errorRate() {
        return errorRate;
    }

    void setErrorRate(double value) {
        this.errorRate = clamp(value);
    }

    double p95LatencyMilliseconds() {
        return p95LatencyMilliseconds;
    }

    void setP95LatencyMilliseconds(double value) {
        this.p95LatencyMilliseconds = Math.max(0.0, value);
    }

    double diskUtilization() {
        return diskUtilization;
    }

    void setDiskUtilization(double value) {
        this.diskUtilization = clamp(value);
    }

    double baseLatency() {
        return switch (spec.kind()) {
            case DATABASE -> 12.0;
            case CACHE -> 3.0;
            case API -> 80.0;
            case GATEWAY -> 45.0;
            case WORKER -> 120.0;
        };
    }

    ServiceHealth health() {
        if (errorRate > 0.25 || p95LatencyMilliseconds > 1500) {
            return ServiceHealth.OUTAGE;
        }
        if (errorRate > 0.05 || p95LatencyMilliseconds > 400 || memoryUtilization > 0.9 || diskUtilization > 0.92) {
            return ServiceHealth.DEGRADED;
        }
        return ServiceHealth.HEALTHY;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
