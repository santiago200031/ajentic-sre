[← Back to README](../README.md) | [← Previous: Schedules & State](07-schedules-and-state.md) | **Next:** [09 - Deployment & Operations →](09-deployment-and-operations.md)

---

# LLM Providers, Failover & Sensitive Data Masking

`ajentic-sre` integrates with LLMs through LangChain4j and Quarkus, supporting local model runners, cloud inference endpoints, automatic endpoint failover, and zero-leak identifier masking.

## LLM Providers

Configured via `ajentic.llm.provider`:

| Provider | Description |
|---|---|
| `openai-compatible` *(default)* | Standard OpenAI `/chat/completions` API (e.g. local llama.cpp, vLLM, Ollama, OpenRouter) |
| `openai`, `azure`, `aws`, `llamacpp`, `vllm` | Supported aliases for compatible wire contracts |
| `scripted` | Fully deterministic offline stub used in tests (no network or model required) |

Default local setup connects to a local llama.cpp router at `http://127.0.0.1:9931/v1`.

### Core Configuration Properties

```properties
ajentic.llm.provider=openai-compatible
ajentic.llm.base-url=http://127.0.0.1:9931/v1
ajentic.llm.api-key=local
ajentic.llm.model=Cyber-Tiel-Coder-35B-A3B-UD-Q4_K_XL
ajentic.llm.timeout=300s
ajentic.llm.temperature=0.0
```

---

## Ordered LLM Endpoint Failover

When `ajentic.llm.fallback-enabled=true`, chat calls route through a resilient composite client:

1. Tries primary endpoint (`ajentic.llm.base-url`).
2. On network timeout, upstream `5xx`, unparseable response, or context limit overflow, fails over to the ordered entries in `ajentic.llm.fallback-endpoints`.
3. Format: `base-url|model|api-key|api-version|api-key-header`, comma-separated.

```properties
ajentic.llm.fallback-enabled=true
ajentic.llm.fallback-endpoints=https://openrouter.ai/api/v1|openai/gpt-4o|${OPENROUTER_API_KEY},https://api.groq.com/openai/v1|llama-3.3-70b-versatile|${GROQ_API_KEY}
```

Masking wraps the outer client, ensuring failover endpoints never receive unredacted sensitive identifiers.

---

## Sensitive Identifier Masking

Every request sent to an LLM passes through `SensitiveDataMaskingInterceptor`, replacing real identifiers with stable `[[CATEGORY_N]]` placeholders and restoring them in the model's response. Categories are ordered longest (most specific) first to prevent partial redaction bugs.

| Category | Placeholder | Patterns Matched |
|---|---|---|
| `ARN` | `[[ARN_N]]` | AWS resource ARNs (`arn:aws:...`) |
| `GCP_RESOURCE` | `[[GCP_N]]` | GCP resource paths (`//*.googleapis.com/...`) |
| `AZURE_RESOURCE` | `[[AZURE_N]]` | Azure resource paths (`/subscriptions/...`) |
| `K8S_OBJECT` | `[[K8S_N]]` | Kubernetes name+UUID pairs (`db-secret:<uuid>`) |
| `API_KEY` | `[[API_KEY_N]]` | `sk-`, `ghp_`, `ghs_`, `github_pat_`, AWS `AKIA...` |
| `BEARER` | `[[BEARER_N]]` | `Bearer <token>` headers |
| `EMAIL` | `[[EMAIL_N]]` | User email addresses |
| `NAMESPACE` | `[[NAMESPACE_N]]` | Qualified cluster / project namespaces |
| `POD` | `[[POD_N]]` | Pod names (`<service>-<hash>-<suffix>`) |
| `IP` | `[[IP_N]]` | IPv4 address literals |
| `ACCOUNT` | `[[ACCOUNT_N]]` | 12-digit cloud account IDs |

---

[← Back to README](../README.md) | [← Previous: Schedules & State](07-schedules-and-state.md) | **Next:** [09 - Deployment & Operations →](09-deployment-and-operations.md)
