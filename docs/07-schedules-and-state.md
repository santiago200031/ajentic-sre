[← Back to README](../README.md) | [← Previous: Sidecar & Security Connectors](06-sidecar-and-security.md) | **Next:** [08 - LLM Configuration & Masking →](08-llm-and-masking.md)

---

# Scheduled Drills, State Persistence & Approval Gates

`ajentic-sre` features a versioned cron scheduler for recurring disaster drills, atomic persistent state storage, and operator approval gates for mutating operations.

## Cron-Backed Scheduled Drills

Drills run on a five-field cron engine backed by `com.cronutils`. Named ranges support step increments (e.g. `mon-fri/2`), and invalid expressions fail validation at ingestion time.

### Creating a Schedule via REST
```bash
SCHEDULE_ID=$(curl -s -X POST http://localhost:8080/api/v1/schedules \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "nightly-database-starvation-drill",
    "cron": "0 3 * * *",
    "scenarioId": "checkout-latency-db-pool"
  }' | jq -r .id)

# Trigger an immediate run outside the cron window
curl -s -X POST "http://localhost:8080/api/v1/schedules/$SCHEDULE_ID/run" | jq .

# Inspect historical drill outcomes
curl -s http://localhost:8080/api/v1/schedules/drills | jq .
```

### Server Engine
In server mode, `QuarkusScheduleTicker` executes `tick()` on a virtual thread. Concurrency is bounded by `ajentic.scheduler.max-concurrent-runs=4`, and transient failures automatically route to a recovery lane (`ajentic.scheduler.recovery-delay=30s`).

---

## Durable State Store

Schedules and drill executions are persisted in `.ajentic/state.json`.

- **Atomic Writes:** Written first to a `.tmp.<uuid>` file, flushed to disk, and moved into place via atomic file system rename (`ATOMIC_MOVE`).
- **Concurrency Locks:** File-based process locks prevent concurrent writer corruption.
- **Backend Selection:** Configured via `ajentic.state.store = file | memory | jdbc | redis` (defaults to `file`, `%test` defaults to `memory`).

---

## Episodic Memory

Completed episodes are saved as JSON lines in `.ajentic/incidents.jsonl`.
- Sensitive identifiers are masked before writing to disk.
- Automatically rotates when file size exceeds `ajentic.memory.max-file-size=1048576` (1MB).
- New interactive sessions search past incidents matching the culprit service and fault kind, appending a concise historical summary to the agent prompt.

---

## Human-in-the-Loop Approval Gates

When mutating actions must be approved by human operators, configure:
```properties
ajentic.tools.mutation-policy=require-approval
ajentic.approval.ttl=10m
```

### Flow
1. When the agent attempts a mutating tool call (`apply_remediation`, `github_commit_file`), the tool returns a `PENDING_APPROVAL` result containing an `approvalId` and expiration timestamp.
2. Read-only tools continue to function unimpeded.
3. Operators list pending approvals:
   ```bash
   curl -s "http://localhost:8080/api/v1/agent/sessions/$SESSION_ID/pending" | jq .
   ```
4. Operator approves or denies:
   ```bash
   curl -s -X POST "http://localhost:8080/api/v1/agent/sessions/$SESSION_ID/approve" \
     -H 'Content-Type: application/json' \
     -d '{"approvalId": "app-12345", "decision": "approve"}' | jq .
   ```
5. Approvals execute the deferred action exactly once and insert the real result into the transcript. Denials and expired requests insert a rejected outcome without executing the action. Repeat decisions are idempotent.

---

[← Back to README](../README.md) | [← Previous: Sidecar & Security Connectors](06-sidecar-and-security.md) | **Next:** [08 - LLM Configuration & Masking →](08-llm-and-masking.md)
