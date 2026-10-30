# ajentic-sre implementation plan

Branch-per-workstream plan for the three tracks. Every branch is independently mergeable, keeps the default test
suite offline (`mvn test`, WireMock stubs, no model, no network), and targets Java 25 / Quarkus 4.0.0.Beta1.

## Branch map and merge order

| # | Branch | Track | Scope | Depends on | Status |
|---|--------|-------|-------|------------|--------|
| 1 | `fix/masking-multiturn-toolcalls` | 1 | Mask prior-turn `toolCalls`; scope masking per session | — | ✅ done |
| 2 | `fix/tool-step-terminal-semantics` | 1 | Read tools through `Environment.step`; terminal only when solved | — | ✅ done |
| 3 | `feat/symptom-first-alerts` | 1 | Unspoiled initial observation | 2 | ✅ done |
| 4 | `fix/cli-mcp-stdio-isolation` | 1 | No `:8080` bind for CLI/MCP; clean stdout | — | ✅ done |
| 5 | `fix/episode-cursor-scoping` | 1 | Per-session/request episode targeting | 4 | ✅ done |
| 6 | `refactor/schedule-scenario-reference` | 1 | Store `scenarioId`, not a lambda; race-free `run` | — | ✅ done |
| 7 | `feat/prometheus-episode-metrics` | 1 | Micrometer counters/timers for episodes, tools, tokens | 2 | ✅ done |
| 8 | `feat/local-docker-stack` | 1 | `docker-compose.yml`: app + Prometheus + Grafana | 7 | ✅ done |
| 9 | `feat/scenario-packs` | 2 | External YAML/JSON scenario loading | 3 | ⬜ todo |
| 10 | `feat/topology-cascading-failures` | 2 | Service DAG + downstream→upstream degradation | 3, 9 | ⬜ todo |
| 11 | `feat/investigation-tools` | 2 | `inspect_recent_changes`, `correlate_anomalies`, topology tool | 10 | ⬜ todo |
| 12 | `feat/stagnation-breaker` | 2 | Duplicate tool-call detection with synthetic hint | 2 | ⬜ todo |
| 13 | `feat/token-usage-budget` | 2 | Cumulative usage in `Conversation`/`ChatResponse` | — | ✅ done |
| 14 | `feat/llm-policy-adapter` | 2 | `@PolicyModel(LLM)` ReAct policy | 2, 13 | ⬜ todo |
| 15 | `feat/benchmark-cli` | 2 | `benchmark` subcommand + scorecard | 14 | ⬜ todo |
| 16 | `feat/environment-backends` | 3 | `ajentic.environment.backend` strategy | 6, 10 | ⬜ todo |
| 17 | `feat/live-telemetry-adapters` | 3 | `local-container` / `cloud-live` read-only adapters | 16 | ⬜ todo |
| 18 | `feat/sse-chat-stream` | 3 | `/chat/stream` SSE | 13 | ⬜ todo |
| 19 | `feat/alert-webhooks` | 3 | Alertmanager/PagerDuty/SNS intake | 16, 18 | ⬜ todo |
| 20 | `feat/llm-fallback-router` | 3 | Local-first composite LLM client | 13 | ⬜ todo |
| 21 | `feat/multicloud-identifier-masking` | 3 | GCP/Azure/K8s/Bearer categories | 1 | ⬜ todo |
| 22 | `feat/approval-gates` | 3 | `ALLOW \| DENY \| REQUIRE_APPROVAL` | 18 | ⬜ todo |
| 23 | `feat/episodic-memory` | 3 | `search_past_incidents` + `.ajentic/incidents.jsonl` | 6, 22 | ⬜ todo |
| 24 | `feat/file-state-store` | 3 | Durable schedules/drills/episodes | 6 | ⬜ todo |

Recommended landing order: 1 → 2 → 4 → 5 → 6 → 3 → 13 → 12 → 21 → 7 → 8 → 9 → 10 → 11 → 14 → 15 → 16 → 17 → 18 →
19 → 20 → 22 → 23 → 24. Branches 1, 2, 4, 6, 13 are independent and can be opened in parallel.

Conventions for every branch:
- `git switch -c <branch> main`; conventional commits (`fix:`, `feat:`, `refactor:`, `test:`, `docs:`).
- New config keys go in `src/main/resources/application.properties` with a `%test` override where needed.
- Every behavior change gets an `@QuarkusTest` or plain JUnit test; live-LLM paths stay behind `-Plive-llm`.
- Update `README.md` command/API tables when a command, endpoint, or config key is added.
- `mvn verify` (offline) must pass before merge.

---

## Track 1 — Local-first polish and core fixes

### 1. `fix/masking-multiturn-toolcalls`

**Problem.** `SensitiveDataMaskingInterceptor.maskRequest` masks `content()` but passes `message.toolCalls()`
through untouched. The assistant message stored in `Conversation` has real identifiers inside tool-call arguments
because `unmaskResponse` restores them. On turn N+1 those arguments are replayed to the provider in the clear.
A fresh `MaskingSession` per LLM call also means placeholders are not stable across one agent loop.

- [x] Mask `ToolCall.arguments()` in `maskRequest` (recursively for nested maps/lists, not only top-level strings).
- [x] Preserve non-string scalars so tool schemas keep their types, with a recursion depth guard.
- [x] Scope one `MaskingSession` per `AgentSession`: create it in `AgentSessionFactory`, bind it in `chat` via
      `AgentExecutionContext.MASKING_SESSION`, and have the interceptor reuse the bound session instead of creating
      a new one per call. Keep the per-call fallback for direct `LlmClient` use.
- [x] Ensure `unmaskResponse` covers `content()` and nested `toolCalls().arguments()`; tool-call ids stay verbatim.
- [x] E2E test: a two-turn session whose first tool call carries an IPv4, an AWS ARN, and an email; every outbound
      WireMock body is free of the originals and reuses `[[IP_1]]` (no `[[IP_2]]`).
- [x] E2E test: nested map/list tool-call arguments are masked before they reach the provider.

**Files.** `agent/masking/SensitiveDataMaskingInterceptor.java`, `agent/masking/MaskingSession.java`,
`agent/session/AgentSession.java`, `agent/session/AgentSessionFactory.java`, `agent/llm/MaskingLlmClient.java`,
`src/test/resources/wiremock/*`.

**Done when.** A grep of recorded outbound requests for a known secret returns zero hits across all turns, and the
masking tests fail on `main` but pass on the branch.

### 2. `fix/tool-step-terminal-semantics`

**Problem.** `query_telemetry`, `read_runbook`, and `list_remediations` call `environment.signals(...)` /
scenario data directly, so read actions never advance `system.tick()` and never incur the step penalty in
`IncidentRewardFunction` (`STEP_COST = -0.02` in the current code, not `-0.05`). Separately
`SimulatedProductionEnvironment.step` sets terminal `RESOLVED` as soon as
`allFaultsResolved()`, even when `diagnosed == false`, so remediation-before-diagnosis ends the episode unsolved.

- [x] Route read tools through `context.environment().step(new QueryTelemetry(...))`, `new ReadRunbook(...)`,
      `new ListRemediations()`; return `result.observation()` content rather than re-querying the environment.
- [x] Return results from `StepResult.observation()` so read tools never bypass `Environment.step`.
- [x] Change terminal logic: `allFaultsResolved()` alone is not `RESOLVED`; require `diagnosed && allFaultsResolved()`.
      Keep `CATASTROPHIC` and `MAX_STEPS` unchanged.
- [x] Keep a remediated-but-undiagnosed episode open so the agent can still declare the root cause.
- [x] Test: parallel read tools increment `step` to 9 instead of leaving the clock at 0.
- [x] Test: `apply_remediation` before `declare_diagnosis` leaves the episode open; the later diagnosis terminates it
      with `diagnosed` and `resolved` both true.
- [x] Test: heuristic policy still reaches `RESOLVED` for every bundled scenario.

**Files.** `agent/tool/builtin/BuiltInSreTools.java`, `rl/simulation/SimulatedProductionEnvironment.java`,
`rl/Observation.java` (if needed), `rl/policy/HeuristicPolicy.java` (if ordering changes),
`src/test/java/io/ajentic/sre/rl/IncidentResponseEndToEndTest.java`.

### 3. `feat/symptom-first-alerts`

**Problem.** `reset(...)` seeds `"Incident detected on " + scenario.culpritService()` and pre-populates signals for
the culprit, so `HeuristicPolicy.resolveTarget` and the LLM are handed the answer.

- [x] Change the initial observation to a symptom on the entry/edge service (e.g. `edge-gateway` or
      `checkout-api` 5xx spike), with no mention of the culprit.
- [x] Seed the initial observation with signals for the alarming service only.
- [x] Update `HeuristicPolicy` to start from the alerting service and scan the remaining services when the first
      one yields no diagnosis.
- [x] Add `Scenario.alertingService()` (defaulting to the first edge-kind service) so scenarios can declare it.
- [x] Test: initial observation text never contains `culpritService()`; a "lazy" policy that reads only the alert
      service cannot solve without further queries.
- [x] Test: the heuristic still solves every bundled scenario after the extra scanning steps.

**Files.** `rl/simulation/SimulatedProductionEnvironment.java`, `rl/simulation/Scenario.java`,
`rl/simulation/ScenarioLibrary.java`, `rl/policy/HeuristicPolicy.java`.

### 4. `fix/cli-mcp-stdio-isolation`

**Problem.** `java -jar .../quarkus-run.jar mcp` (and `ask`, `scenarios`, `simulate`) boots the full HTTP stack and
collides with `serve` on `:8080`; Quarkus logs/banner on stdout corrupt the MCP JSON-RPC stream.

- [x] Add `CliMain` (`@QuarkusMain`) that disables the HTTP listener for every command except `serve`, while a
      bare `java -jar` still starts the server.
- [x] Send logs to stderr and disable the banner so stdout stays clean.
- [x] Reuse the single CDI `McpProtocolHandler` in the stdio command (no second registry).
- [x] Verify manually: server on `:8080`, then `scenarios` and `mcp` run concurrently; stdout of `mcp` parses as
      JSON-RPC on every line.
- [x] Test (`CliEndToEndTest`): headless detection plus the `@QuarkusMainTest` launches for `scenarios`, `simulate`,
      and `schedules`.
- [x] Document the two-process workflow in `README.md`.

**Files.** `cli/AjenticCommand.java`, `cli/McpStdioCommand.java`, `cli/ServeCommand.java`,
`src/main/resources/application.properties`, `src/main/docker/Dockerfile.jvm`.

### 5. `fix/episode-cursor-scoping`

**Problem.** `EpisodeCursor` is one `@ApplicationScoped AtomicReference<String>`; concurrent MCP/HTTP clients
overwrite each other's active episode unless every call passes `_episodeId`.

- [x] Replace the global cursor with a per-caller strategy: explicit `_episodeId` wins, else an MCP session
      (`Mcp-Session-Id`) or agent-session scoped cursor.
- [x] Bound the cursor map (Caffeine, 1024 entries) so long-lived servers cannot leak session ids.
- [x] Brand-new MCP sessions start from `ajentic.mcp.default-scenario`, as before.
- [x] Test: two interleaved MCP sessions start different scenarios and each resolves its own episode without
      `_episodeId`.
- [x] HTTP transport returns and accepts `Mcp-Session-Id` so spec-compliant clients keep their scope.

**Files.** `rl/episode/EpisodeCursor.java`, `agent/mcp/McpProtocolHandler.java`, `agent/tool/builtin/BuiltInSreTools.java`,
`agent/session/*`.

### 6. `refactor/schedule-scenario-reference`

**Problem.** `ScheduleDefinition` holds an in-memory `ScheduledJob` closure and `ScheduleResource.run` reads
`drills.recent().getLast()` after `runNow(id)`, which is racy under concurrent drills.

- [x] Replace `ScheduledJob job` with `String scenarioId` in `ScheduleDefinition`; build the job from the stored
      id via `IncidentDrillRunner`.
- [x] Make `Scheduler.runNow(id)` return the produced `EpisodeResult`, so `ScheduleResource` no longer reads
      `drills.recent().getLast()`.
- [x] Keep the store serializable: only ids, cron, flags, zone, version, group, scenario id.
- [x] Preserve version/optimistic-lock behavior in `InMemoryScheduleStore`.
- [x] Keep in-memory lambda schedules for tests via a transient side map, so `SchedulerEndToEndTest` stays intact.
- [x] Test: existing scheduler and API suites pass, including create → run → `solved=true` for the scheduled
      scenario.

**Files.** `schedule/store/ScheduleDefinition.java`, `schedule/store/InMemoryScheduleStore.java`,
`schedule/engine/Scheduler.java`, `schedule/drill/IncidentDrillRunner.java`,
`api/resource/ScheduleResource.java`, `api/payload/ScheduleView.java`.

### 7. `feat/prometheus-episode-metrics`

- [x] Report `ajentic.episodes.started`, `ajentic.episodes.finished{solved}` (the solved/unresolved split replaces a
      separate `episodes.solved` counter), `ajentic.episode.reward`, `ajentic.episode.steps`,
      `ajentic.tool.invocations{tool,capability}`, `ajentic.tool.latency{tool}`, and `ajentic.llm.tokens{kind,model}`.
- [x] Instrument `EpisodeService` and `AgentSession` (tool invocation and every LLM response); tags are scenario id,
      tool name, capability, and the configured model, all bounded configuration values.
- [x] Expose `/q/metrics` (already served by the Prometheus registry) and assert the metric names through the API.
- [x] No `Dockerfile.jvm` change needed; the endpoint is served by the existing build.

**Files.** new `metrics/` package (or `health/`), `rl/episode/EpisodeService.java`, `agent/session/AgentSession.java`,
`agent/llm/OpenAiCompatibleLlmClient.java`.

### 8. `feat/local-docker-stack`

- [x] Add `docker-compose.yml`: `ajentic-sre` (port 8080, `AJENTIC_LLM_BASE_URL` defaulting to
      `http://host.docker.internal:9931/v1`), `prometheus` scraping `/q/metrics`, and `grafana` with a provisioned
      dashboard. Ollama and LocalStack are left out until a branch needs them.
- [x] Use `extra_hosts: host.docker.internal:host-gateway` for Linux.
- [x] Add the Grafana dashboard JSON (episodes, reward, tool p95, tokens) and the Prometheus scrape config.
- [x] Add `make lab-up` / `make lab-down`; `make test` stays offline.
- [x] Document the stack and ports in `README.md`; validate with `docker compose config`.

**Files.** `docker-compose.yml`, `src/main/docker/` (prometheus, grafana), `Makefile`, `README.md`.

---

## Track 2 — Deeper SRE simulation and model benchmarking

### 9. `feat/scenario-packs`

- [ ] Add `ajentic.scenarios.dir` (default `./scenarios`, empty = disabled) to `ScenarioLibrary`; load `*.yaml`,
      `*.yml`, `*.json` at startup and on demand.
- [ ] Define a stable external schema mirroring `Scenario`, `ServiceSpec`, `Fault`, `Runbook`; validate on load and
      fail fast with file+line context.
- [ ] Merge external packs with built-ins; external ids may not shadow built-ins (or must opt in explicitly).
- [ ] Include JSON Schema + 2 sample packs (a DB pool exhaustion and a cascading gateway incident).
- [ ] Test: load a temp directory, list scenarios, run an episode; bad file yields a clear startup error.
- [ ] Document authoring in `README.md`.

**Files.** `rl/simulation/ScenarioLibrary.java`, new `rl/simulation/pack/*`, `application.properties`,
`src/test/resources/scenarios/`.

### 10. `feat/topology-cascading-failures`

- [ ] Add `List<String> dependencies` to `ServiceSpec` and a validated `ServiceTopology` DAG (no cycles, all refs
      resolve).
- [ ] Propagate downstream latency/error-rate degradation upstream in `SimulatedSystem.applyFaultEffects`, with
      depth-decayed severity and a `cascadeFactor` per edge.
- [ ] Extend `Scenario.services()` construction helpers so built-ins declare realistic graphs
      (edge-gateway → checkout-api → orders-db / redis-cache).
- [ ] Add `@AgentTool(name = "get_service_topology")` and MCP resource `sre://topology/{scenarioId}`.
- [ ] Test: fault on a leaf degrades all ancestors; cycles rejected at load; topology tool output matches the DAG.
- [ ] Test: symptom-first alert appears on the edge service while the culprit is a leaf.

**Files.** `rl/simulation/ServiceSpec.java`, `Scenario.java`, `SimulatedSystem.java`,
`agent/tool/builtin/BuiltInSreTools.java`, `agent/mcp/McpProtocolHandler.java`.

### 11. `feat/investigation-tools`

- [ ] Add `inspect_recent_changes(service, window)` backed by `DeployRecord` plus new config/flag/quota change
      records; return git-style diffs, feature-flag flips, and quota changes.
- [ ] Add `correlate_anomalies(services[], window)` ranking metric shifts across dependent services (z-score or
      relative delta) using the topology from branch 10.
- [ ] Make both READ-capability, step-counted (branch 2), and deterministic per seed.
- [ ] Test: a deploy-correlated scenario where the right change is discoverable; correlation ranks the culprit
      service first.
- [ ] Test: tools respect `maxSteps` and reward penalties.

**Files.** `rl/simulation/DeployRecord.java`, `rl/simulation/SimulatedSystem.java`,
`agent/tool/builtin/BuiltInSreTools.java`, `rl/TelemetrySignal.java` (if a new signal type is warranted).

### 12. `feat/stagnation-breaker`

- [ ] In `AgentSession.chat`, keep a per-turn `(toolName, canonicalizedArguments)` multiset; on the 2nd identical
      call return a synthetic observation hint ("You already called X with Y; try Z") without re-executing.
- [ ] On the 3rd identical call, escalate: suggest unused tools and, after a configurable threshold, stop the loop
      with a diagnostic answer rather than burning `maxIterations`.
- [ ] Config: `ajentic.agent.stagnation.threshold` (default 2), `ajentic.agent.stagnation.escalate-after` (3).
- [ ] Ensure synthetic results are still appended as `tool` messages so the transcript stays protocol-valid.
- [ ] Test: a scripted LLM that repeats the same call 5 times terminates early with a hint, not `maxIterations`.
- [ ] Test: legitimately repeated reads across turns are not flagged.

**Files.** `agent/session/AgentSession.java`, `agent/session/Conversation.java`,
`agent/session/AgentSessionConfig.java`, `application.properties`.

### 13. `feat/token-usage-budget`

- [x] Accumulate `TokenUsage` (prompt/completion/total) in `Conversation`; expose `Conversation.usage()`.
- [x] Add usage to `ChatTurnResult` and `ChatResponse` as a new trailing field.
- [x] Enforce an optional hard budget (`ajentic.agent.max-tokens`, default `0` = unlimited): stop before the next
      LLM call once cumulative usage is reached, and answer with a clear budget message. A separate soft warning
      prompt is not implemented.
- [x] Token metrics from branch 7 stay fed per LLM response.
- [x] Test: a scripted turn reports the summed usage (49 tokens) and a one-token budget stops before the second
      LLM call.
- [x] Test: `/api/v1/agent/sessions/{id}/chat` JSON includes `usage.totalTokens`.

**Files.** `agent/session/Conversation.java`, `AgentSession.java`, `ChatTurnResult.java`,
`api/payload/ChatResponse.java`, `api/resource/AgentResource.java`.

### 14. `feat/llm-policy-adapter`

- [ ] Add `LLM` to `PolicyKind` (and `RANDOM` for the benchmark baseline) and register the new policy with the
      existing `@PolicyModel` qualifier / `PolicyFactory` lookup.
- [ ] Add `@PolicyModel(PolicyKind.LLM) LlmPolicy implements Policy`, bridging the `Policy` contract to the ReAct
      loop (one `AgentSession` per episode, `chat`/`chatUntilGoal`).
- [ ] Map `Observation` → prompt, tool calls → `AgentAction`, terminal → episode end; reuse masking and tools.
- [ ] Guard against infinite loops (`maxIterations`, stagnation from branch 12) and surface usage.
- [ ] Test with `ScriptedLlmClient`: a scripted policy solves a scenario; unknown/scenario mismatch yields a clean
      failure, not an exception.
- [ ] Test: masking is active for LLM policies (no raw identifiers in stub requests).

**Files.** new `rl/policy/LlmPolicy.java`, `rl/policy/PolicyModel.java`/registry, `agent/session/*`,
`rl/episode/EpisodeService.java`.

### 15. `feat/benchmark-cli`

- [ ] Add `benchmark` subcommand: `--scenarios all|<ids> --seeds N --model <id> --policy HEURISTIC|RANDOM|LLM`.
- [ ] Run every (scenario, seed) pair, collect solve rate, diagnosis accuracy, steps to resolution, prompt/completion
      tokens, and p95 latency; emit a table plus `--json` output.
- [ ] Compare HEURISTIC, RANDOM, and one or more LLMs side by side; write results to `.ajentic/benchmarks/<ts>.json`.
- [ ] Keep it offline by default against `scripted`; `-Plive-llm` or `--model` opts into a live endpoint.
- [ ] Test: a 2-scenario × 2-seed run produces a deterministic scorecard for HEURISTIC/RANDOM.
- [ ] Document sample invocations in `README.md` and `AGENTS.md` command table.

**Files.** new `cli/BenchmarkCommand.java`, `cli/AjenticCommand.java`, new `benchmark/` package.

---

## Track 3 — Live observability and multi-cloud adapters

### 16. `feat/environment-backends`

- [ ] Add `ajentic.environment.backend = simulated | local-container | cloud-live` and make `EnvironmentFactory`
      select an implementation (`@ConfigProperty` + CDI alternatives or a small strategy map).
- [ ] Keep `simulated` the default and the only backend used by tests/CI.
- [ ] Define a backend SPI around `Environment` (`signals`, `step`, `observe`, `spec`) so adapters do not leak into
      the simulator.
- [ ] Fail fast on unknown backend values; log the active backend at startup.
- [ ] Test: config selects each backend; simulated remains byte-for-byte deterministic.
- [ ] Document backend matrix in `README.md`.

**Files.** `rl/simulation/EnvironmentFactory.java`, `rl/Environment.java` (if the SPI needs widening),
`application.properties`.

### 17. `feat/live-telemetry-adapters`

- [ ] `local-container`: `query_telemetry` queries local Prometheus (`/api/v1/query_range`) and Loki
      (`/loki/api/v1/query_range`) via JDK `HttpClient`; `apply_remediation` restarts/scales local containers or
      LocalStack resources (ECS/Lambda/SSM).
- [ ] `cloud-live` (read-only): map telemetry to Prometheus/Grafana Tempo/Loki or native cloud REST APIs (AWS AMP,
      GCP GMP, Azure Monitor) — no heavy cloud SDK BOMs.
- [ ] Enforce read-only in `cloud-live` regardless of `ajentic.tools.allow-mutations`; `apply_remediation` returns a
      "shadow mode" result.
- [ ] Add timeouts, bounded retries, and a circuit breaker; never block a virtual thread indefinitely.
- [ ] Tests: WireMock/local HTTP stubs for Prometheus/Loki responses; a mutation attempt in `cloud-live` is rejected;
      upstream 5xx surfaces as a tool error, not a crash.
- [ ] Document required endpoints/env vars per environment.

**Files.** new `rl/environment/local/*`, `rl/environment/cloud/*`, `agent/tool/builtin/BuiltInSreTools.java`
(delegate through `Environment` only), `application.properties`.

### 18. `feat/sse-chat-stream`

- [ ] Add `POST /api/v1/agent/sessions/{id}/chat/stream` returning `text/event-stream`.
- [ ] Emit typed events: `thought`, `tool_call`, `tool_result`/`observation`, `usage`, `done`, `error` — one per
      ReAct step, flushed as produced.
- [ ] Push events from `AgentSession` via a listener/callback so the non-streaming path is unchanged.
- [ ] Handle client disconnect (cancel the turn, release the session), heartbeats for long local-model calls, and
      correct `Content-Type`/`Cache-Control`.
- [ ] Test with REST Assured streaming: a scripted 2-iteration turn yields events in order and a terminal `done`.
- [ ] Document `curl -N` usage.

**Files.** `api/resource/AgentResource.java` (or new `AgentStreamResource`), `agent/session/AgentSession.java`,
new `agent/session/TurnListener.java`.

### 19. `feat/alert-webhooks`

- [ ] Add `POST /api/v1/webhooks/alertmanager`, `/pagerduty`, `/cloudwatch-sns` with signature/secret verification
      and payload normalization to an internal `Alert`.
- [ ] On intake: create/lookup an `AgentSession`, start or reuse an episode, run investigation (sync or async with
      SSE/polling), and post the root-cause report through `NotificationChannel` (log/Slack/webhook).
- [ ] Deduplicate by alert fingerprint; enforce a concurrency limit and a timeout for runaway investigations.
- [ ] Persist the intake → session → report linkage for postmortems (feeds branch 23).
- [ ] Test: an Alertmanager payload creates a session and posts a report via a capturing channel; duplicate
      fingerprint is coalesced; bad signature → 401.
- [ ] Document payloads and required config.

**Files.** new `api/resource/WebhookResource.java`, `api/payload/AlertPayload.java`,
`agent/integration/*`, `agent/session/SessionService.java`.

### 20. `feat/llm-fallback-router`

- [ ] Extend `LlmClientProducer` with a composite client: local llama.cpp/Ollama (`127.0.0.1:9931`) primary, ordered
      OpenAI-compatible fallbacks (Bedrock/Vertex/Azure OpenAI/OpenRouter) on connect/timeout/5xx.
- [ ] Add optional escalation for complex turns (tool-call parse failure or context overflow) rather than failover
      only on transport errors.
- [ ] Config: `ajentic.llm.fallback.enabled`, `ajentic.llm.fallback.endpoints[]`, per-endpoint model/key env refs;
      never log secrets.
- [ ] Keep masking applied once around the composite so failover cannot bypass redaction.
- [ ] Test with two WireMock endpoints: primary 500 → fallback used and response returned; both down → single clear
      `LlmException`.
- [ ] Test: masking intercepts requests to both endpoints.

**Files.** new `agent/llm/CompositeLlmClient.java`, `LlmClientProducer.java`, `LlmProperties.java`,
`application.properties`.

### 21. `feat/multicloud-identifier-masking`

- [ ] Extend `SensitiveDataCategory` with GCP resource paths (`//*.googleapis.com/projects/...`), Azure resource
      ids (`/subscriptions/.../resourceGroups/...`), Kubernetes pod/secret names+uid patterns, and
      `Bearer <token>` / common API-key shapes.
- [ ] Order patterns longest-first and add negative tests to avoid over-masking (e.g. plain paths, UUIDs).
- [ ] Verify masking works inside nested tool-call arguments (branch 1) and SSE payloads (branch 18).
- [ ] Test per category: placeholder round-trip, id stability, and no leakage in outbound WireMock bodies.
- [ ] Document the category table in `README.md`.

**Files.** `agent/masking/SensitiveDataCategory.java`, `SensitiveDataMasker.java`, tests.

### 22. `feat/approval-gates`

- [ ] Replace boolean `allow-mutations` with `ALLOW | DENY | REQUIRE_APPROVAL` in `DefaultToolAuthorization` while
      keeping `ajentic.tools.allow-mutations` as a backward-compatible alias.
- [ ] Model a pending approval: `invoke` returns a `PENDING_APPROVAL` tool result containing an approval id; the
      agent may continue with READ tools.
- [ ] Add `POST /api/v1/agent/sessions/{id}/approve` (approve/deny, optional note, expiry, single-use) and a
      `GET .../pending` listing.
- [ ] On approval, execute the deferred mutating call and append the real tool result to `Conversation`;
      on denial/expiry, append a denied result.
- [ ] Test: `REQUIRE_APPROVAL` blocks `apply_remediation`, READ tools still work, approve executes exactly once,
      deny/expiry never executes.
- [ ] Test: concurrent approvals are idempotent.

**Files.** `agent/tool/DefaultToolAuthorization.java`, `agent/tool/ToolAuthorization.java`,
`agent/session/AgentSession.java`, `api/resource/AgentResource.java`, new `agent/approval/*`.

### 23. `feat/episodic-memory`

- [ ] Persist completed `EpisodeResult` + postmortem to `.ajentic/incidents.jsonl` (append-only, fsync-batched,
      rotation by size); debounce/serialize writes.
- [ ] Add `@AgentTool(name = "search_past_incidents")` searching by symptom text, service, and fault kind; return the
      top-k prior resolutions with reward/solved outcome.
- [ ] Inject a short "similar past incidents" summary into the system prompt when available (bounded token budget).
- [ ] Ensure PII/identifiers are masked before persistence.
- [ ] Test: run → persist → new session searches and finds it; corrupt line is skipped, not fatal; masking verified
      on disk.
- [ ] Add `.ajentic/` to `.gitignore`; document the store location and opt-out config.

**Files.** new `agent/memory/IncidentMemoryStore.java`, `agent/tool/builtin/BuiltInSreTools.java`,
`rl/episode/EpisodeService.java`, `application.properties`.

### 24. `feat/file-state-store`

- [ ] Add a file-backed `ScheduleStore` (`.ajentic/state.json`) and durable drill/episode history; atomic
      write-temp-then-rename, file lock for concurrent processes, schema version field.
- [ ] Keep `InMemoryScheduleStore` for tests; select via `ajentic.state.store = memory | file | jdbc`.
- [ ] Persist only serializable fields — enabled by branch 6 (`scenarioId`, not a lambda).
- [ ] Define the `jdbc`/Redis seam now (interface + config), implement later.
- [ ] Test: create schedules → restart the app context → schedules and versions survive; crash-safety (partial temp
      file is ignored).
- [ ] Add `.ajentic/` to `.gitignore`; document backup/inspect commands.

**Files.** new `schedule/store/FileScheduleStore.java`, new `state/` package, `application.properties`,
`schedule/drill/IncidentDrillRunner.java`.

---

## Cross-cutting definition of done

- [ ] `mvn verify` passes offline; no new network calls in the default profile.
- [ ] Test count grows with each branch; no `@Disabled` left behind without a linked follow-up.
- [ ] `README.md` updated for new commands, endpoints, config keys, and backend matrix.
- [ ] No secrets or real infrastructure identifiers in logs, fixtures, or `.ajentic/` artifacts.
- [ ] New public types carry Javadoc consistent with the existing style; 4-space indent, LF, final newline.
- [ ] `AGENTS.md` command table updated when a CLI subcommand or profile is added.

## Suggested first sprint

1. `fix/masking-multiturn-toolcalls` — security bug, small diff, high value.
2. `fix/tool-step-terminal-semantics` — unblocks honest RL/benchmark numbers.
3. `fix/cli-mcp-stdio-isolation` — makes daily local use pleasant.
4. `refactor/schedule-scenario-reference` — prerequisite for all persistence work.
5. `feat/symptom-first-alerts` — makes the benchmark meaningful.
