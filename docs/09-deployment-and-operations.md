[← Back to README](../README.md) | [← Previous: LLM & Masking](08-llm-and-masking.md) | **Next:** [10 - Testing & Extensibility →](10-testing-and-extensibility.md)

---

# Deployment, Packaging & Local Observability

## Building & Packaging

The application builds with standard Maven targeting Java 25:

```bash
# Compile, run full offline tests, and assemble runner
mvn package

# The packaged fast-jar structure is created at:
# target/quarkus-app/quarkus-run.jar
```

### Running Locally
```bash
# Server starts on port 8080 (sole entrypoint)
java -jar target/quarkus-app/quarkus-run.jar

# Dev mode with live coding and Dev UI on http://localhost:8080/q/dev-ui
mvn quarkus:dev
```

---

## Container Deployment

The container image runs on Linux with Eclipse Temurin:

```bash
mvn package
docker build -f src/main/docker/Dockerfile.jvm -t ajentic-sre .
docker run -i --rm -p 8080:8080 ajentic-sre
```

Pass configuration as environment variables, for example:
- `QUARKUS_HTTP_PORT=8080`
- `AJENTIC_LLM_BASE_URL=http://host.docker.internal:9931/v1`
- `AJENTIC_LLM_MODEL=Cyber-Tiel-Coder-35B-A3B-UD-Q4_K_XL`
- `AJENTIC_TOOLS_MUTATION_POLICY=require-approval`

---

## Local Observability Lab (Prometheus & Grafana)

A complete local observability stack is pre-configured via `docker-compose.yml`:

```bash
# Start application + Prometheus (:9090) + Grafana (:3000)
docker compose up -d ajentic-sre prometheus grafana

# Tear down stack
docker compose down
```

- **Application:** `http://localhost:8080`
- **Prometheus:** `http://localhost:9090` (scrapes `/q/metrics` every 15s)
- **Grafana:** `http://localhost:3000` (anonymous admin enabled, with pre-provisioned dashboard **"Ajentic SRE"**)

---

## Multi-Cloud Emulation Stack

To spin up local cloud emulation (LocalStack, Azurite, and stubbed telemetry endpoints):

```bash
# Build and spin up the multi-cloud emulation stack
mvn package -DskipTests
docker compose --profile cloud up --build -d

# Seed simulated cloud state and fleets
bash src/main/docker/seed/seed-clouds.sh

# Tear down the cloud emulation stack
docker compose --profile cloud down
```

---

[← Back to README](../README.md) | [← Previous: LLM & Masking](08-llm-and-masking.md) | **Next:** [10 - Testing & Extensibility →](10-testing-and-extensibility.md)
