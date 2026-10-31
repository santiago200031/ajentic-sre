[← Back to README](../README.md) | [← Previous: Architecture](01-architecture.md) | **Next:** [03 - MCP Server Protocol →](03-mcp.md)

---

# HTTP-First REST API & cURL Cookbook

`ajentic-sre` is an HTTP-first service. Root `GET /` redirects (`303 See Other`) directly to Swagger UI at `/q/swagger-ui`. OpenAPI v3 schema is hosted at `/q/openapi`, readiness/liveness at `/q/health`, and Prometheus metrics at `/q/metrics`.

## Endpoint Reference

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Redirects (`303`) to `/q/swagger-ui` |
| `GET` | `/api/v1/scenarios` | Lists all built-in and external scenarios |
| `GET` | `/api/v1/scenarios/{id}` | Fetches scenario details, topology, and runbooks |
| `GET` | `/api/v1/tools` | Discovers available agent tool definitions |
| `POST` | `/api/v1/episodes` | Initiates a new incident episode |
| `GET` | `/api/v1/episodes/{id}` | Inspects current episode state and history |
| `POST` | `/api/v1/episodes/{id}/actions` | Submits a single action to the environment |
| `POST` | `/api/v1/episodes/{id}/run` | Executes a policy until episode completion |
| `POST` | `/api/v1/episodes/simulate` | One-shot simulation without creating persistent sessions |
| `DELETE` | `/api/v1/episodes/{id}` | Discards an episode |
| `POST` | `/api/v1/agent/sessions` | Creates an LLM agent interactive session |
| `GET` | `/api/v1/agent/sessions/{id}` | Retrieves session transcript and status |
| `POST` | `/api/v1/agent/sessions/{id}/chat` | Executes a single ReAct loop turn |
| `POST` | `/api/v1/agent/sessions/{id}/chat/stream` | Streams ReAct thoughts and tool calls via SSE |
| `POST` | `/api/v1/agent/ask` | High-level one-shot question investigation |
| `POST` | `/api/v1/benchmarks` | Executes comparative benchmark sweeps |
| `GET` | `/api/v1/schedules` | Lists cron-scheduled incident drills |
| `POST` | `/api/v1/schedules` | Creates a versioned recurring drill schedule |
| `POST` | `/api/v1/schedules/{id}/run` | Triggers immediate drill execution |

## cURL Cookbook

### 1. Inspect Scenarios
```bash
curl -s http://localhost:8080/api/v1/scenarios | jq .
```

### 2. Run a One-Shot Simulation
Simulate a scenario using the deterministic heuristic policy:
```bash
curl -s -X POST http://localhost:8080/api/v1/episodes/simulate \
  -H 'Content-Type: application/json' \
  -d '{"scenarioId": "orders-bad-deployment", "policy": "heuristic", "seed": 42}' | jq .
```

### 3. Ask the Agent to Investigate
```bash
curl -s -X POST http://localhost:8080/api/v1/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{
    "scenarioId": "checkout-latency-db-pool",
    "question": "Why is checkout-api latency spiking?",
    "seed": 42
  }' | jq .
```

### 4. Benchmark Policies
Evaluate policies across multiple seeds and scenarios:
```bash
curl -s -X POST http://localhost:8080/api/v1/benchmarks \
  -H 'Content-Type: application/json' \
  -d '{
    "scenarios": ["orders-bad-deployment", "checkout-latency-db-pool"],
    "seeds": 3,
    "policies": ["HEURISTIC", "RANDOM"]
  }' | jq .
```

### 5. Stream ReAct Events via Server-Sent Events (SSE)
```bash
# 1. Start a session
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/v1/agent/sessions \
  -H 'Content-Type: application/json' \
  -d '{"scenarioId": "orders-bad-deployment", "seed": 1}' | jq -r .id)

# 2. Stream the investigation
curl -N -s -X POST "http://localhost:8080/api/v1/agent/sessions/$SESSION_ID/chat/stream" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"message": "Investigate why orders-api is returning errors."}'
```

Event stream types:
- `thought`: Intermediate chain-of-thought reasoning from the model.
- `tool_call`: Invocation parameters for a selected tool.
- `tool_result`: Output returned from the tool execution.
- `usage`: Per-turn token consumption metrics.
- `done`: Final diagnostic conclusion and episode outcome.
- `: heartbeat`: Keepalive comment emitted every 10 seconds.

---

[← Back to README](../README.md) | [← Previous: Architecture](01-architecture.md) | **Next:** [03 - MCP Server Protocol →](03-mcp.md)
