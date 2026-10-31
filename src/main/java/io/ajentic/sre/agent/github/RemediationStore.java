package io.ajentic.sre.agent.github;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store for tracking automated code remediation records across CI polling attempts.
 */
@ApplicationScoped
public class RemediationStore {

    private final Map<String, RemediationRecord> records = new ConcurrentHashMap<>();

    public void record(RemediationRecord record) {
        if (record != null && record.id() != null) {
            records.put(record.id(), record);
        }
    }

    public Optional<RemediationRecord> findById(String id) {
        return Optional.ofNullable(records.get(id));
    }

    public Optional<RemediationRecord> findLatestForRepo(String repo) {
        return records.values().stream()
                .filter(r -> r.repository() != null && r.repository().equalsIgnoreCase(repo))
                .max((a, b) -> a.createdAt().compareTo(b.createdAt()));
    }

    public List<RemediationRecord> pending() {
        return records.values().stream()
                .filter(r -> "PENDING".equalsIgnoreCase(r.ciStatus()))
                .toList();
    }

    public List<RemediationRecord> all() {
        return new ArrayList<>(records.values());
    }

    public void update(RemediationRecord updated) {
        if (updated != null && updated.id() != null) {
            records.put(updated.id(), updated);
        }
    }

    public void clear() {
        records.clear();
    }
}
