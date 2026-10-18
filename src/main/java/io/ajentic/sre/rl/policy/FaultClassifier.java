package io.ajentic.sre.rl.policy;

import io.ajentic.sre.rl.ObservedSignal;
import io.ajentic.sre.rl.simulation.FaultKind;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@ApplicationScoped
public class FaultClassifier {

    public Optional<Diagnosis> classify(List<ObservedSignal> signals) {
        for (ObservedSignal signal : signals) {
            FaultKind kind = match(evidenceOf(signal));
            if (kind != null) {
                return Optional.of(new Diagnosis(signal.service(), kind.rootCause(), kind.requiredRemediation()));
            }
        }
        return Optional.empty();
    }

    private static String evidenceOf(ObservedSignal signal) {
        return (signal.summary() + " " + signal.attributes().values()).toLowerCase(Locale.ROOT);
    }

    private static FaultKind match(String evidence) {
        if (evidence.contains("outofmemory") || evidence.contains("gc overhead") || evidence.contains("heap")) {
            return FaultKind.MEMORY_LEAK;
        }
        if (evidence.contains("too many connections") || evidence.contains("connection is not available")
                || evidence.contains("pool")) {
            return FaultKind.DB_POOL_EXHAUSTION;
        }
        if (evidence.contains("certificate") || evidence.contains("x509")) {
            return FaultKind.CERTIFICATE_EXPIRY;
        }
        if (evidence.contains("no such host") || evidence.contains("servfail") || evidence.contains("dns")) {
            return FaultKind.DNS_FAILURE;
        }
        if (evidence.contains("no space left") || evidence.contains("disk usage")) {
            return FaultKind.DISK_PRESSURE;
        }
        if (evidence.contains("deploy") && (evidence.contains("5xx") || evidence.contains("exception"))) {
            return FaultKind.BAD_DEPLOYMENT;
        }
        return null;
    }
}
