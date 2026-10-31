[← Back to README](../README.md) | [← Previous: Deployment & Operations](09-deployment-and-operations.md)

---

# Testing Strategy & External Extensibility

## Testing Strategy

The test suite runs **100% offline** with zero external network dependencies or live models required.

### Running Tests
```bash
# Full offline test suite (236 tests)
mvn test

# Single test class
mvn test -Dtest=ServerStartupTest

# Java 27 Preview profile
mvn test -Pjdk27-preview

# Live LLM smoke test (optional, requires live endpoint at AJENTIC_LLM_BASE_URL)
mvn test -Plive-llm
```

### Test Suite Architecture

| Test Area | Key Classes | What It Validates |
|---|---|---|
| **Incident Resolution Baselines** | `IncidentResponseEndToEndTest` | Verifies heuristic policy solves all scenarios deterministically |
| **Agent ReAct Loop** | `AgentSessionEndToEndTest`, `SseChatStreamEndToEndTest` | Multi-turn reasoning against WireMock LLM stubs, SSE streaming |
| **MCP Protocol** | `McpProtocolEndToEndTest` | JSON-RPC 2.0 tools, prompts, resources over HTTP & stdio |
| **Pact Consumer Contracts** | `LlmConsumerPactContractTest` | Consumer-driven contract verification for OpenAI-compatible endpoints |
| **Security & Sidecars** | `SidecarConnectorsEndToEndTest` | HMAC-SHA256 signature verification, replay protection, audit trail |
| **Cloud Providers** | `CloudProviderAdaptersTest`, `LiveTelemetryEnvironmentTest` | AWS SigV4, Azure IMDS/OAuth2, GCP service account JWT signing |
| **Alert Webhooks** | `WebhookEndToEndTest` | Multi-cloud intake, deduplication, and GitHub PR remediation |
| **Durable State** | `FileStateStoreTest`, `SchedulerEndToEndTest` | Atomic filesystem persistence, versioned schedules, lock safety |

---

## External Scenario Packs

Add custom incident scenarios without recompiling by setting `ajentic.scenarios.dir` to a directory containing YAML or JSON scenario pack files.

- Schema: Validated against `examples/scenarios/scenario-pack.schema.json`.
- Precedence: Built-in scenario IDs take precedence if an external ID collides.

### Sample Scenario Pack (`scenario-pack.yaml`)
```yaml
id: custom-db-exhaustion
title: Database connection pool saturation
summary: Checkout latency climbs as the checkout API starves for connections.
services:
  - {name: edge-gateway, kind: GATEWAY, replicas: 3, dependencies: [checkout-api]}
  - {name: checkout-api, kind: API, replicas: 4, dependencies: [samples-db]}
  - {name: samples-db, kind: DATABASE, replicas: 1}
faults:
  - {kind: DB_POOL_EXHAUSTION, service: checkout-api, severity: 0.8}
runbooks:
  - {id: rb-db-pool, title: Database pool saturation, body: Raise the connection pool limit and verify metrics recover.}
maxSteps: 12
```

---

[← Back to README](../README.md) | [← Previous: Deployment & Operations](09-deployment-and-operations.md)
