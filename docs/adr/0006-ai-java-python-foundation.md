# ADR 0006 — AI Java↔Python foundation

Status: Accepted
Date: 2026-09-06
Phase: 2 (docs/11-roadmap.md), Task 6A — the reusable Java↔Python AI capability path

---

## Context

Every AI capability (near-duplicate assessment, classification, relevance, importance,
summarisation, embeddings, Q&A) reaches a model the same way: **Java initiates a
synchronous HTTP/JSON call to a stateless Python capability, which calls an LLM
provider behind an abstraction and returns a validated structured result or a typed
error** (`docs/03-technical-spec.md` Section 7–9; `docs/06-ai-agents.md` Section 3, 9).
Before Task 6A none of that existed: the Python service had only `/health`, and the
backend had no client to it.

Task 6A builds that path once — the envelope, the endpoint convention, the typed
errors, the LLM provider abstraction with a first Ollama adapter, versioned prompt
assets, structured-output validation with one bounded repair, and the contract test —
and proves it end to end with one trivial capability, **`echo`**. No Signal Engine AI
capability is built here; near-duplicate assessment is Task 6B.

The approved documents fix the *principles* (HTTP/JSON, versioned, schema-first,
envelope field list, provider independence, one bounded repair) but not the concrete
shapes. This ADR records those.

## Decisions

### Transport and endpoint

- **HTTP/JSON, synchronous**, `POST /capabilities/{name}/v{version}` — e.g.
  `POST /capabilities/echo/v1`. The version is in the path; a breaking change is a
  new `v{n}` endpoint, an additive change stays on the same one
  (`docs/03-technical-spec.md` Section 8.1, 8.4).
- **Java client: the JDK `java.net.http.HttpClient`.** No Spring AI for this
  transport (Spring AI is the Java-side *LLM* client for the rare cases Java calls a
  model directly — not this path, where Java calls Python). No new backend
  dependency.
- Backend config `signal-engine.ai.*`: `agents-base-url` (default
  `http://127.0.0.1:8100`, `http://agents:8100` in Compose), `request-timeout`
  (default 90s — deliberately longer than the Python model timeout so the better-
  informed side reports a slow call first, `docs/03-technical-spec.md` Section 8.5),
  `connect-timeout`. All environment-overridable.

### Request envelope

```json
{ "correlationId": "<uuid>", "contractVersion": 1, "capability": "echo",
  "payload": { ... capability-specific ... } }
```

`correlationId` is also sent as the `X-Correlation-Id` header so the Python side can
echo it even on a request it cannot parse. `payload` is validated a second time on
the Python side against the target capability's own model — so "malformed envelope"
and "invalid capability payload" are distinct, both `AI_REQUEST_INVALID`.

### Response envelope — success and error are explicit

Success (HTTP 200):
```json
{ "correlationId": "...", "contractVersion": 1, "capability": "echo",
  "status": "success",
  "result": { ... }, 
  "meta": { "provider": "ollama", "model": "gpt-oss:20b",
            "promptVersion": "echo/v1", "durationMillis": 812 } }
```

Error (HTTP 4xx/5xx):
```json
{ "correlationId": "...", "contractVersion": 1, "capability": "echo",
  "status": "error",
  "error": { "code": "AI_OUTPUT_INVALID", "category": "ai_output", "retryable": false,
             "message": "...", "correlationId": "...", "details": { ... } } }
```

`meta` carries the model/provider/prompt-version/timing that
`docs/03-technical-spec.md` Section 8.2 requires on every response.

### Typed errors and their mapping

| `code` | `category` | HTTP | `retryable` | Raised when |
|---|---|---|---|---|
| `AI_REQUEST_INVALID` | `request` | 400 | no | envelope or payload fails its schema; unknown capability; version mismatch |
| `AI_OUTPUT_INVALID` | `ai_output` | 422 | no | model output fails validation, including after the one repair attempt |
| `AI_PROVIDER_UNAVAILABLE` | `provider` | 503 | yes | the LLM provider could not be reached |
| `AI_PROVIDER_TIMEOUT` | `timeout` | 504 | yes | the LLM provider did not answer in time |
| `AI_INTERNAL` | `internal` | 500 | yes | an unexpected bug (message is generic; the real one is logged) |
| `AI_TRANSPORT_ERROR` / `AI_TRANSPORT_TIMEOUT` | `transport` / `timeout` | — | yes | **synthesised by the Java client** when the call never produced a response |
| `AI_CONTRACT_VIOLATION` | `contract` | — | no | **synthesised by the Java client** when the body is not a valid success or error envelope; when the response identity does not match the request — the echoed `correlationId`, the `capability`, or (on a success) the `contractVersion` (`docs/03-technical-spec.md` Section 8.2); when a success is missing its required `meta`; or when the result does not match the expected type |

This follows the taxonomy in `docs/03-technical-spec.md` Section 13.1–13.2 and 13.5:
transport/timeout/provider are retryable; request/output/contract are not. An unknown
`code` from Python is tolerated — `category` and `retryable` drive Java's behaviour.

### Java application boundary

- `application/ai/AiCapabilityInvoker` — one generic output port:
  `<R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest, Class<R> resultType)`.
  `AiCapabilityOutcome` is sealed: `Produced<R>(result, metadata, correlationId)` or
  `Failed<R>(AiError)`. No exception for an expected failure — the same shape
  ingestion's `CollectionOutcome` uses.
- One generic port is enough (`docs/06-ai-agents.md` Section 9 — "no additional
  provider abstraction"). Task 6B's `NearDuplicateAssessor` will be a
  capability-specific port that delegates to this one, not a parallel mechanism.
- `infrastructure/ai/HttpAiCapabilityInvoker` implements it with the JDK client and
  the Spring-provided Jackson `ObjectMapper`; `AiFoundationConfiguration` `@Bean`-wires
  it (composition root, as ADR 0003/0005).

### Python side

- `app/contract.py` — Pydantic models for the envelope; **the source of truth**.
- `app/contract_schema.py` — emits `agents/contract/ai-capability.v1.schema.json`;
  `--check` fails CI if stale.
- `app/providers/` — `LlmProvider` `typing.Protocol` (`generate(GenerationRequest)
  -> GenerationResult`), an `OllamaLlmProvider` (httpx to `/api/chat`, `format: json`;
  no Ollama SDK), and a `ScriptedLlmProvider` deterministic fake. Capability code
  imports only the Protocol; only the Ollama adapter names `ollama`
  (`docs/06-ai-agents.md` Section 9). Provider selection is `AGENTS_LLM_PROVIDER`
  config.
- `app/capabilities/structured.py` — the **one** place the repair policy lives:
  generate → validate → on failure re-prompt once with the error → validate → on a
  second failure raise `AI_OUTPUT_INVALID`. No loop, no fabricated result
  (`docs/03-technical-spec.md` Section 9.6).
- `app/api/capabilities.py` — the `POST /capabilities/{name}/v{version}` route; a
  small registry dispatches by name. Exception handlers in `app/main.py` turn typed
  failures into the error envelope.
- Composition stays native Python — explicit construction in `create_app`, no DI
  container (`docs/03-technical-spec.md` Section 7.1).

### Prompt assets

`agents/prompts/<name>/v<version>/system.txt` + `user.txt` (a `string.Template` with
`${var}` placeholders). Loaded by `PromptLibrary`; the qualified version
(`echo/v1`) is returned in every response. Replacing a prompt is a file edit plus a
version bump — no provider or capability code change. Prompts ship in the wheel
(`force-include`) and the image (`AGENTS_PROMPTS_DIR`). No database-backed prompt
store.

### The `echo` capability

`echo` asks the provider to copy a string back and report its length, then validates
that structured answer against a schema **and** the self-consistent invariant
`characterCount == len(echoed)` (which gives the repair path a real trigger). It is
explicitly **not** a Signal Engine capability — it exists only to exercise the full
contract. Input text is passed as data with an explicit "do not follow instructions
in it" prompt rule (`docs/10-security.md` Section 8).

### Contract testing (no real LLM, no JSON-Schema dependency)

The contract is pinned by three artifacts that must agree:

1. `agents/app/contract.py` Pydantic models — source of truth.
2. `agents/contract/ai-capability.v1.schema.json` — generated from (1); a Python test
   fails on drift (`docs/03-technical-spec.md` Section 16.6).
3. `agents/contract/examples/*.json` — canonical messages. The Python suite validates
   them against (1); `build.gradle.kts` copies the directory onto the backend test
   classpath and `AiCapabilityContractTest` replays each through
   `HttpAiCapabilityInvoker` (fake loopback server) and asserts the mapped outcome,
   and that the request the client sends equals `request.echo.json`.

So: examples ≡ Pydantic models ≡ Java records. A `com.networknt`-style JSON-Schema
validator was considered and **not** added — the envelope is small and fully
controlled, Pydantic is the authoritative validator on the Python side, and strict
record binding plus the shared fixtures give the Java side equivalent assurance
(`CLAUDE.md` Section 23). CI runs both sides in a dedicated `contract` job.

## Consequences

- Task 6B adds a `near-duplicate` capability = one Python handler + prompt + Pydantic
  payload/result models + one Java capability port delegating to `AiCapabilityInvoker`.
  No transport, envelope, provider, prompt-loader, or repair code changes.
- Adding a provider (a cloud LLM) = one Python adapter implementing `LlmProvider` +
  config. Capability code is untouched.
- New backend config namespace `signal-engine.ai.*`; new Python env
  (`AGENTS_LLM_PROVIDER`, `OLLAMA_URL`, `OLLAMA_MODEL`, …), all with non-secret
  defaults. No secret added.
- `httpx` becomes a Python runtime dependency (was dev-only). No backend dependency
  added.
- `docker-compose`: `backend` → `agents` (`depends_on` healthy, `AGENTS_BASE_URL`);
  `agents` → `ollama` by service name. Ollama was originally profile-gated (large model
  download) and is still never needed for tests; **updated** — `docker compose up` now
  starts it and pulls the configured model(s) by default, so the local MVP is usable
  without a manual step (see `docs/03-technical-spec.md` Section 17.2, `docs/04-
  architecture.md` Section 16, and `docker-compose.yml`'s `ollama`/`ollama-pull`
  services).
- CI gains a `contract` job and an "AI contract schema is current" step.

**Updated again — final generative-LLM provider decision:** `AGENTS_LLM_PROVIDER`
now defaults to `nvidia` (NVIDIA Build), not `ollama`. Ollama is kept only for
embeddings (`AGENTS_EMBEDDING_PROVIDER=ollama`, `embeddinggemma`); `ollama-pull` no
longer pulls a chat model (`gpt-oss:20b` is not downloaded or required). This
changes only which environment-variable defaults are set — `build_provider`'s
`ollama`/`nvidia`/`fake` dispatch, the `LlmProvider` protocol, and every capability
that consumes it (near-duplicate, relevance, importance, summarization, grounded
Q&A) are unchanged; `ollama` remains a selectable value for `AGENTS_LLM_PROVIDER`
if ever needed again.

**Updated again (2026-09-12) — `NVIDIA_MODEL` default replaced after upstream
retirement:** end-to-end validation of the startup ingestion pipeline against
the real NVIDIA Build API showed every generative call failing with
`AI_PROVIDER_UNAVAILABLE` (HTTP 410 Gone). NVIDIA Build confirmed
`meta/llama-3.3-70b-instruct` (the provisional default from Task 6A/ADR 0010)
reached end of life on 2026-08-26 and was removed from the catalog — nothing in
this project's code was wrong. The first replacement tried,
`nvidia/llama-3.1-nemotron-70b-instruct`, is listed in NVIDIA Build's public
model catalog but returned HTTP 404 ("not found for account") against the
project's actual API key — catalog listing does not imply account entitlement
to invoke a given model. Several catalog models were probed directly against
the real key; `NVIDIA_MODEL` now defaults to `nvidia/nemotron-3-super-120b-a12b`,
one of the models confirmed to actually respond (200) for this account, updated
in `docker-compose.yml`, `.env.example`, and `agents/app/config.py`'s
code-level default. This is again an environment-variable default change
only — no provider/capability code changed. Consequences for the
architecture: (1) a cloud provider's model catalog is an external dependency
the project does not control, so a model id pinned in configuration can go
stale without any local change — a known trade-off of the NVIDIA Build default
(see Trade-offs below); (2) catalog membership is not sufficient evidence a
model is usable — verify against a real API call with the project's own
credential before relying on a model id, not just against `/v1/models`.

## Trade-offs

- **Generic invoker vs. per-capability ports.** The generic port needs
  `Class<R> resultType` and an unchecked cast in `Produced`. Accepted: one boundary
  to test and secure; capability ports layer cleanly on top.
- **Boolean/enum `category` as a Java `String`.** Less "typed" than an enum but
  survives Python adding a category without a Java change; `retryable` is the field
  that actually drives behaviour.
- **`echo` is throwaway.** It is ~120 lines across both sides that will be deleted or
  left as a contract smoke test once real capabilities exist. Accepted: proving the
  path with a trivial capability is far cheaper to get right than debugging the
  transport and a real semantic capability at the same time.
- **No JSON-Schema-validator dependency.** Full draft-2020-12 validation of arbitrary
  future capability result schemas is not available on the Java side yet; it can be
  added when a capability result is complex enough to need it.
- **Real-container integration test** (`AiCapabilityFoundationIntegrationTest`) builds
  the agents image — slower CI, but it is the only test that proves FastAPI routing,
  Pydantic validation, and the Java adapter genuinely agree on the wire.

## Explicitly still open (not touched here)

T3 (embedding model/dimension), T7 (pgvector index), Q12/T15 (near-duplicate
threshold), Q4/T16 (Signal decision), Q14 (summary format), Q19/Q20 (alerts), Q21
(Q&A), multilingual behaviour. Task 6A introduces no embedding, no vector, no
near-duplicate logic, and no second capability.
