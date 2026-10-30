# ajentic-sre

ajentic-sre is a practice range for AI agents that handle infrastructure incidents. It stages fake
production failures, gives an agent tools to look into them, and grades whether the agent found the real
root cause and brought the service back. Nothing here touches live systems, and the tests run without a
model or a network.

Written in Java 25 on Quarkus 4. Runs on Linux.

## Quick start

```bash
mise install          # JDK 27 and Maven 3.10, per .mise.toml
mvn quarkus:dev       # dev server at http://localhost:8080
mvn test              # offline end-to-end suite
```

## Contents

| Topic | What you'll find |
| --- | --- |
| [How it works](#how-it-works) | The incident loop, the agent, and the scenarios |
| [Install](#install) | Requirements and build |
| [Run](#run) | Dev mode, packaged jar, CLI |
| [Deploy](#deploy) | Container image and configuration |
| [Use the CLI](#use-the-cli) | `scenarios`, `simulate`, `ask`, `schedules`, `serve`, `mcp` |
| [Use the REST API](#use-the-rest-api) | Episodes, sessions, schedules, OpenAPI |
| [Use MCP](#use-mcp) | stdio and streamable HTTP transports |
| [Scheduled drills](#scheduled-drills) | Cron-backed recurring incidents |
| [Configure the LLM](#configure-the-llm) | Providers, local llama.cpp, Azure |
| [Test](#test) | End-to-end, WireMock, Pact, live mode |
| [Project layout](#project-layout) | Packages and responsibilities |
| [JDK support](#jdk-support) | Java 25 default, Java 27 preview |

## How it works

An episode is one incident. A scenario sets up the failure, and the environment reports what is wrong. From
there the agent acts one step at a time until it repairs the service, runs out of budget, or makes things
worse.

1. `reset()` returns the alert, the telemetry, and the actions on offer.
2. The agent investigates with read-only tools (`query_telemetry`, `read_runbook`, `list_remediations`).
3. It records a root cause with `declare_diagnosis` and applies a fix with `apply_remediation`.
4. Each step returns an observation, a reward, and a reason if the episode ended.
5. The result reports `solved`, `resolved`, `diagnosed`, the total reward, and the full trajectory.

The agent runs a ReAct loop. `AgentSession.chat()` sends the conversation to the model, runs the tool calls
it gets back against the environment, feeds the results into the conversation, and repeats until the model
stops asking for tools or the episode ends. You can also leave the model out and run a baseline policy.

<details>
<summary><strong>RL interface</strong></summary>

| Type | Responsibility |
| --- | --- |
| `Environment` | `reset`, `step`, `observe`, `spec`, terminal flags |
| `Observation` | incident, telemetry, service health, available actions, cumulative reward |
| `AgentAction` | sealed action model: telemetry, runbook, remediations, diagnosis, idle |
| `StepResult` | next observation, reward, termination reason |
| `RewardFunction` | maps a `Transition` to a scalar reward |
| `Policy` | chooses the next action from an observation |
| `EpisodeRunner` | rolls out a policy and collects an `EpisodeResult` |

Rewards credit a correct diagnosis and remediation, charge a small cost per step, and penalise mistakes.
Repeated wrong remediations turn catastrophic and end the episode.

</details>

<details>
<summary><strong>Scenarios</strong></summary>

| Scenario | Root cause | Correct remediation |
| --- | --- | --- |
| `checkout-latency-db-pool` | database connection pool exhaustion | `SCALE_UP` |
| `payments-memory-leak` | application memory leak | `RESTART` |
| `auth-certificate-expiry` | expired TLS certificate | `FIX_CONFIG` |
| `search-dns-failure` | cluster DNS resolution failure | `FAILOVER` |
| `orders-bad-deployment` | bad application deployment | `ROLLBACK_DEPLOY` |
| `storage-disk-pressure` | node disk pressure | `EXPAND_DISK` |

Each scenario defines services, injected faults, runbooks, and a step budget. Telemetry comes from the
simulated service state, so metrics, logs, traces, and deploys stay consistent with one another.

</details>

## Install

You need Linux, JDK 25 or newer (JDK 27 for the preview profile), and Maven 3.9 or newer. `mise install`
pulls the pinned toolchain from `.mise.toml`; if you don't use mise, install the JDK and Maven yourself.

```bash
mvn package        # compile, test, and build target/quarkus-app/
mvn verify         # full validation, including packaging
```

`make dev`, `make test`, `make verify`, and `make package` are thin wrappers around the same commands.

## Run

```bash
java -jar target/quarkus-app/quarkus-run.jar         # server on :8080
mvn quarkus:dev                                      # dev mode with live reload
mvn quarkus:dev -Dquarkus.args="scenarios"           # run a CLI command in dev mode
```

## Deploy

```bash
mvn package
docker build -f src/main/docker/Dockerfile.jvm -t ajentic-sre .
docker run -i --rm -p 8080:8080 ajentic-sre
```

The image is Linux-only and runs on Eclipse Temurin 27. Pass configuration as environment variables, for
example `AJENTIC_LLM_BASE_URL`, `AJENTIC_LLM_MODEL`, or `QUARKUS_HTTP_PORT`.

## Use the CLI

```bash
ajentic-sre <command> [options]
```

Commands other than `serve` start without the HTTP listener, so they can run while a server owns `:8080`. Logs go
to stderr; stdout carries only command output (and the MCP JSON-RPC stream for `mcp`).

| Command | Purpose |
| --- | --- |
| `scenarios` | List the scenario library |
| `simulate --scenario <id> [--policy heuristic\|idle] [--seed 42]` | Run a scenario with a baseline policy |
| `ask "<question>" [--scenario <id>]` | Ask the LLM agent one question |
| `schedules [--add <name> --cron '<cron>' --scenario <id>] [--tick] [--run <name>]` | Manage drills |
| `serve` | Start the HTTP API and block |
| `mcp` | Serve tools over MCP on stdin/stdout |

## Use the REST API

Everything lives under `/api/v1`. OpenAPI is at `/q/openapi`, Swagger UI at `/q/swagger-ui`, health at
`/q/health`, and Prometheus metrics at `/q/metrics` (episode, tool, and LLM token counters/timers).

```bash
curl -s localhost:8080/api/v1/scenarios | jq
curl -s localhost:8080/api/v1/tools | jq

EPISODE=$(curl -s -X POST localhost:8080/api/v1/episodes \
  -H 'content-type: application/json' \
  -d '{"scenarioId":"orders-bad-deployment"}' | jq -r .id)

curl -s -X POST localhost:8080/api/v1/episodes/$EPISODE/run \
  -H 'content-type: application/json' \
  -d '{"policy":"heuristic"}' | jq
```

<details>
<summary><strong>Endpoint reference</strong></summary>

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/scenarios`, `/scenarios/{id}` | Scenario library |
| `GET` | `/tools` | Tool catalog |
| `POST` | `/episodes` | Start an episode |
| `GET` | `/episodes/{id}` | Episode state |
| `POST` | `/episodes/{id}/actions` | Apply one action |
| `POST` | `/episodes/{id}/run` | Run a policy to completion |
| `DELETE` | `/episodes/{id}` | Forget an episode |
| `POST` | `/agent/sessions` | Start an LLM session |
| `GET` | `/agent/sessions/{id}` | Session state |
| `POST` | `/agent/sessions/{id}/chat` | One ReAct turn |
| `DELETE` | `/agent/sessions/{id}` | End a session |
| `GET` / `POST` / `DELETE` | `/schedules` … | See [scheduled drills](#scheduled-drills) |
| `GET` / `POST` / `DELETE` | `/mcp` | See [MCP](#use-mcp) |

</details>

## Use MCP

The tool registry speaks Model Context Protocol over JSON-RPC 2.0. Two transports share one handler, so an
MCP host and a REST client see the same tools:

```bash
mvn quarkus:dev -Dquarkus.args="mcp"      # stdio, for local MCP hosts

curl -s localhost:8080/api/v1/mcp \       # streamable HTTP
  -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq
```

Methods: `initialize`, `ping`, `tools/list`, `tools/call`, `resources/list`, `resources/read`, `prompts/list`,
and `prompts/get`. `POST` takes one request or a batch array, and malformed JSON comes back as `-32700`.
`GET` returns discovery metadata; `DELETE` clears the active episode.

- Lifecycle tools: `list_scenarios`, `start_episode`, `run_incident_drill`.
- Resources: `sre://scenarios/{scenarioId}` and `sre://runbooks/{scenarioId}/{runbookId}`.
- Prompts: `investigate-incident` and `summarize-postmortem`.
- A `tools/call` can pass `_scenarioId` to start an episode, or `_episodeId` to reuse one.

## Scheduled drills

Recurring incidents run on a five-field cron engine backed by `com.cronutils`. Named ranges accept steps
(`mon-fri/2`, `jan-dec/3`), `*/15` starts from the earliest minute, and invalid expressions fail at parse
time. A schedule's identity ignores the loop-group id, so duplicates are refused and stopped schedules can
be added again. Writes are versioned, which keeps a stale update from resurrecting or overwriting.

```bash
SCHEDULE=$(curl -s -X POST localhost:8080/api/v1/schedules \
  -H 'content-type: application/json' \
  -d '{"name":"nightly-drill","cron":"0 3 * * *","scenarioId":"orders-bad-deployment"}' | jq -r .id)

curl -s -X POST localhost:8080/api/v1/schedules/$SCHEDULE/run | jq
curl -s localhost:8080/api/v1/schedules/drills | jq
```

In server mode `QuarkusScheduleTicker` drives `tick()` on a virtual thread. The CLI and tests call `tick()`
themselves, which keeps them deterministic. Dispatch is capped by a concurrency limit, and failed or
deferred runs go to a recovery lane.

## Configure the LLM

`ajentic.llm.provider` picks the client. The shipped default points at a local llama.cpp router on
`http://127.0.0.1:9931/v1`.

| Provider | Behaviour |
| --- | --- |
| `openai-compatible` *(default)* | Any OpenAI `/chat/completions` endpoint |
| `openai`, `azure`, `aws`, `llamacpp`, `ollama`, `vllm`, `wiremock` | Aliases for the same wire contract |
| `scripted` | Fully offline client, used by the `%test` profile |

Azure appends `ajentic.llm.api-version` and authenticates with the `api-key` header. Every other provider
sends `Authorization: Bearer <key>`, unless `ajentic.llm.api-key-header` says otherwise.

<details>
<summary><strong>Common properties</strong></summary>

| Property | Default | Notes |
| --- | --- | --- |
| `ajentic.llm.provider` | `openai-compatible` | Client selection |
| `ajentic.llm.base-url` | `http://127.0.0.1:9931/v1` | Endpoint root |
| `ajentic.llm.api-key` | `local` | Left out of the request when empty |
| `ajentic.llm.model` | `Qwen3.8-27B-GSQ-RCO-IQ3_XXS-mtp` | Model name |
| `ajentic.llm.timeout` | `300s` | Local models can be slow to load |
| `ajentic.llm.temperature` | `0.0` | Deterministic sampling |
| `ajentic.llm.chat-completions-path` | `/chat/completions` | For gateways with a custom path |
| `ajentic.llm.api-version` | | Azure only |
| `ajentic.llm.api-key-header` | | Override the auth header |
| `ajentic.tools.allow-mutations` | `true` | Gate for mutating tools |
| `ajentic.mcp.default-scenario` | `checkout-latency-db-pool` | Episode bootstrap |

</details>

## Test

`mvn test` needs no model and no network.

| Test | Covers |
| --- | --- |
| `IncidentResponseEndToEndTest` | Heuristic baseline solves every scenario |
| `AgentSessionEndToEndTest` | ReAct loop over the real HTTP client against WireMock |
| `LlmConsumerPactContractTest` | OpenAI-compatible and Azure wire contracts (`target/pacts/`) |
| `SchedulerEndToEndTest` | Cron correctness, versioning, concurrency, recovery |
| `ApiEndToEndTest` | REST surface, including the MCP HTTP transport |
| `McpProtocolEndToEndTest` | The JSON-RPC handler the stdio command uses |
| `CliEndToEndTest` | Picocli commands |

Stubs live in `src/test/resources/wiremock/`, started by `WireMockLlmTestResource`. To point the suite at a
live endpoint instead, run `mvn test -Plive-llm` and override with `AJENTIC_LLM_BASE_URL` or
`AJENTIC_LLM_MODEL`. Those assertions depend on the model following the incident, so treat them as a smoke
test. The default run stays offline.

## Project layout

<details>
<summary><strong>Packages</strong></summary>

```
io.ajentic.sre
├── rl                  environment contracts, rewards, episodes, policies, simulation
├── agent
│   ├── context         ScopedValue execution context for virtual threads
│   ├── llm             Jackson 3 client, scripted and OpenAI-compatible
│   ├── masking         per-request identifier masking and unmasking
│   ├── mcp             transport-agnostic MCP JSON-RPC handler
│   ├── session         ReAct session, conversation, goals, session registry
│   └── tool            tool contract, CDI registry, built-in tools
├── schedule            cron, versioned store, bounded engine, drill runner
├── api                 JAX-RS resources, mappers, typed payloads
├── cli                 Picocli commands, including the MCP stdio bridge
└── health              liveness and readiness checks
```

Tools are `@AgentTool` methods on `@ToolBox` beans. The registry reads their signatures to build the JSON
Schema and bind arguments, and prompts work the same way through `@AgentPrompt`. Read-only tools run in
parallel windows of eight on virtual threads, while mutations run one at a time behind an authorization
gate. Request context travels as Java 25 `ScopedValue`s. Identifier masking is a CDI interceptor around
the LLM client, and the episode and session registries are bounded Caffeine caches.

</details>

## JDK support

The default build targets Java 25 (`maven.compiler.release=25`) and runs on Java 25 or newer. The
`jdk27-preview` profile switches to `--release 27 --enable-preview`. Both profiles pass the full suite.

```bash
mvn test                 # Java 25
mvn test -Pjdk27-preview # Java 27 preview
```
