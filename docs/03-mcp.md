[← Back to README](../README.md) | [← Previous: HTTP API & Cookbook](02-http-api.md) | **Next:** [04 - ChatOps & OpenAI Facade →](04-chatops-and-facade.md)

---

# Model Context Protocol (MCP) Server

`ajentic-sre` provides a native **Model Context Protocol (MCP)** server via Quarkiverse MCP Server, supporting both streamable HTTP (`/mcp` and `/mcp/sse`) and stdio transports.

## Transport Modes

### 1. HTTP Transport (Default)
Available at `/mcp` (Streamable JSON-RPC) and `/mcp/sse` (Server-Sent Events).
- Set `Accept: application/json, text/event-stream` for streamable RPC.
- Each initialization returns an `Mcp-Session-Id` header for stateful session routing.

```bash
# Initialize MCP session
curl -s -i -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": {"name": "curl-client", "version": "1.0"}
    }
  }'
```

### 2. Stdio Transport
Enabled by setting `AJENTIC_MCP_STDIO=true` (or `quarkus.mcp.server.stdio.enabled=true`).
Stdio mode reads and writes JSON-RPC directly to standard input/output. Application banners and logging are automatically redirected to stderr.

## Tools Catalog

Tools are registered via `@ToolBox` and `@AgentTool` CDI annotations.

### Lifecycle & Navigation
- `list_scenarios`: Discovers all available simulated incident scenarios.
- `start_episode`: Launches an incident episode for an agent to solve.
- `run_incident_drill`: Runs a fully automated drill with baseline policy evaluation.

### Investigation (Read-Only)
- `query_telemetry`: Fetches metrics, error rates, p95 latency, and logs for a target service.
- `inspect_recent_changes`: Inspects simulated git commits and deployment history.
- `correlate_anomalies`: Detects correlated metric deviations across the dependency graph.
- `get_service_topology`: Retrieves the service DAG and dependency links.
- `read_runbook`: Reads operational guidance and standard operating procedures.
- `list_remediations`: Lists viable remediation actions for the current state.
- `search_past_incidents`: Queries episodic memory for past postmortems.

### Remediation (Mutating)
- `apply_remediation`: Executes an operational fix (`RESTART`, `SCALE_UP`, `ROLLBACK_DEPLOY`, `FIX_CONFIG`, `FAILOVER`, `EXPAND_DISK`).
- `declare_diagnosis`: Submits root cause diagnosis to conclude investigation.
- `post_incident_summary`: Publishes postmortem summaries to notification channels.

### GitHub Integration (Mutating / Read)
- `github_list_files`, `github_read_file`, `github_get_ci_status` (Read-only).
- `github_create_branch`, `github_commit_file`, `github_create_pull_request`, `github_trigger_workflow` (Mutating).

## MCP Resources

Exposes environment state and metadata under standard URI schemes:
- `sre://scenarios/{scenarioId}`: Detailed scenario configuration, services, and fault definitions.
- `sre://topology/{scenarioId}`: Machine-readable service dependency graph.
- `sre://runbooks/{scenarioId}/{runbookId}`: Playbook content for a given scenario.

## MCP Prompts

Reusable prompt templates exposed to MCP clients:
- `investigate-incident`: Pre-populates context and initial investigation instructions for an alert.
- `summarize-postmortem`: Formats resolved incident trajectory into an executive postmortem.

---

[← Back to README](../README.md) | [← Previous: HTTP API & Cookbook](02-http-api.md) | **Next:** [04 - ChatOps & OpenAI Facade →](04-chatops-and-facade.md)
