# ajentic-sre

Practice range for AI agents that handle infrastructure incidents. Java 25 on Quarkus 4.0.0.Beta1, Linux only.
Nothing touches live systems; the test suite runs offline (no model, no network) against WireMock stubs.

## Toolchain & setup

```bash
mise install          # pins JDK 27 and Maven 3.10 (see .mise.toml)
mvn -v                # confirm Maven 3.10 + JDK 25+
```

- Default build targets Java 25 (`maven.compiler.release=25` in pom.xml). JDK 27 is installed but the preview
  profile opts into `--release 27 --enable-preview`.
- No `mvnw` wrapper; use system `mvn`.
- No CI workflows, pre-commit config, or `opencode.json` in this repo.

## Commands (verified)

| Goal | Command |
|------|---------|
| Full offline test suite | `mvn test` |
| One test class | `mvn test -Dtest=ServerStartupTest` |
| Compile + test + package | `mvn package` |
| Full validation | `mvn verify` |
| Dev server (live reload) | `mvn quarkus:dev`  → http://localhost:8080 |
| Packaged server | `java -jar target/quarkus-app/quarkus-run.jar` (after `mvn package`) |
| Docker image | `mvn package && docker build -f src/main/docker/Dockerfile.jvm -t ajentic-sre .` |
| Thin wrappers | `make dev \| test \| verify \| package \| lab-up \| cloud-up` |

`mvn test` needs `-Dtest` to run a single class; without it the whole `@QuarkusTest` suite runs.
Surefire is configured with `<skipITs>true</skipITs>`, so failsafe integration tests are skipped by default.

## Profiles

- `mvn test -Pjdk27-preview`: Java 27 preview (`--release 27 --enable-preview`); full suite passes.
- `mvn test -Plive-llm`: bypasses WireMock and calls a live OpenAI-compatible endpoint. Requires a model +
  network; override with `AJENTIC_LLM_BASE_URL` / `AJENTIC_LLM_MODEL`. Treat as a smoke test. The default run
  stays offline via `%test.ajentic.llm.provider=scripted` in `application.properties`.

## Test quirks

- Stubs live in `src/test/resources/wiremock/`; `WireMockLlmTestResource` starts WireMock on a dynamic port and
  points `LangChain4jLlmClient` at it. Set `-Dajentic.llm.live=true` (or `-Plive-llm`) to go live
  instead.
- Test HTTP port is 8081 (`quarkus.http.test-port`); dev/prod port is 8080.
- Dev mode (`mvn quarkus:dev`) runs like local production: connects to your real local LLM router (`127.0.0.1:9931`),
  real GitHub API, and local telemetry services without starting WireMock.
- Mutating tools are gated by `ajentic.tools.mutation-policy` (`allow` | `deny` | `require-approval`); the legacy
  `ajentic.tools.allow-mutations` boolean is the fallback alias. With `require-approval`, mutating calls defer to
  operator approval (`ApprovalGateEndToEndTest`, `ApprovalExpiryEndToEndTest`), covered offline by WireMock.
- Live telemetry backends (`local-container` / `cloud-live`) are tested offline: `LiveTelemetryEnvironmentTest`
  stubs Prometheus/Loki with a programmatic WireMock on a dynamic port; no external services needed.

## Entrypoints & layout

- Server entrypoint: `java -jar target/quarkus-app/quarkus-run.jar` always serves HTTP on `:8080`.
- Root redirect: `GET /` redirects (303) directly to Swagger UI at `/q/swagger-ui`.
- REST surface under `/api/v1`; OpenAPI at `/q/openapi`, Swagger UI at `/q/swagger-ui`, health at `/q/health`.
  - Scenarios: `GET /api/v1/scenarios`
  - Simulation: `POST /api/v1/episodes/simulate`
  - Agent ask: `POST /api/v1/agent/ask`
  - Benchmarks: `POST /api/v1/benchmarks`
  - Schedules: `GET /api/v1/schedules`, `POST /api/v1/schedules`, `POST /api/v1/schedules/{id}/run`
  - Alert webhook: `POST /api/v1/webhooks/alerts` accepts CloudWatch, Azure Monitor, Alertmanager, or direct JSON.
    Normalizes severity (`INFO < WARNING < ERROR < CRITICAL`), ignores below `ajentic.alerts.min-severity` (`202 Accepted`),
    and executes a bounded code-remediation turn (`200 OK`) opening a branch and PR on GitHub (offline stubbed in tests).
- MCP protocol: Quarkiverse MCP Server stdio and HTTP (`/mcp`) transports.
- Packages under `io.ajentic.sre`:
  - `rl`: environment contracts, rewards, episodes, policies, simulation
  - `agent/{llm,session,mcp,tool,masking,context,alerts,github,integration}`: ReAct loop, LLM clients, MCP handler, tool registry, alert normalizer, GitHub client, sidecar client
  - `connectors`: credential-isolated sidecar security (HMAC-SHA256, anti-replay) and audit logging
  - `schedule/{cron,drill,engine,store}`: versioned cron scheduler
  - `state`: file-backed durable state store (`.ajentic/state.json`) with concurrency locks
  - `api`: JAX-RS resources, mappers, payloads
  - `health`

## Conventions

- `.editorconfig`: UTF-8, LF, final newline, trimmed trailing whitespace, 4-space indent (2-space for
  yml/yaml/json/toml; tabs for Makefile).
- Config lives in `src/main/resources/application.properties`; LLM settings are `ajentic.llm.*`.
- Default LLM provider `openai-compatible` points at `http://127.0.0.1:9931/v1` (local llama.cpp router).
