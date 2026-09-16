# Signal Engine — Architecture

Document ID: `04-architecture.md`
Status: Draft — awaiting review
Depends on: `CLAUDE.md`, `docs/01-product-spec.md`, `docs/02-functional-spec.md`,
`docs/03-technical-spec.md`

---

## 1. Purpose and Scope

### 1.1 Purpose

This document describes **how the already-approved Signal Engine system is
organized**: its runtime components, their boundaries, the direction of their
dependencies, and how they communicate. It turns the technical decisions of
`docs/03-technical-spec.md` into a coherent structural picture.

### 1.2 What this document is not

- It is **not** the product specification. It does not say what Signal Engine
  does for the user, what a signal is, or which areas of interest exist — that
  is `docs/01-product-spec.md`.
- It is **not** the functional specification. It does not describe user
  workflows, screens, or behavior — that is `docs/02-functional-spec.md`.
- It is **not** the technical specification. It does not restate library
  versions, dependency justifications, exact retry/timeout values, or CI/CD
  pipeline steps — that is `docs/03-technical-spec.md`, which this document
  references rather than repeats.
- It does **not** define the database schema (`docs/05-data-model.md`), the
  concrete AI agent prompts/contracts (`docs/06-ai-agents.md`), the RAG
  retrieval design (`docs/07-rag.md`), or concrete source connectors
  (`docs/08-ingestion.md`).
- It does **not** introduce a new technology, a new architectural pattern, or a
  new decision. Every statement here traces back to `CLAUDE.md`,
  `docs/01-product-spec.md`, `docs/02-functional-spec.md`, or
  `docs/03-technical-spec.md`.

### 1.3 How to read this document

Where this document needs a concrete value already fixed elsewhere (a version, a
timeout, a retry count), it references the source section instead of restating
it, so the two documents cannot drift. Where a question is still open in
`docs/03-technical-spec.md` (or in the product/functional specifications), this
document names it and leaves it open (Section 19) — it does not resolve it by
implication.

---

## 2. Architectural Overview

Signal Engine's MVP architecture is a **modular Java backend** (Clean
Architecture / Ports and Adapters) plus **one Python AI service**, a single
**PostgreSQL** database with **pgvector**, and a **React** frontend that talks
only to the backend. This is a deliberate, bounded shape — not a distributed
system (`docs/03-technical-spec.md` Sections 2, 25).

```
                                    User
                                     │
                                     ▼
                            React Frontend
                        (presentation only; no DB access)
                                     │
                                     │  REST/JSON over HTTP (OpenAPI, versioned)
                                     ▼
                     ┌───────────────────────────────┐
                     │      Java Spring Backend       │
                     │                                │
                     │  ┌──────────────────────────┐  │
                     │  │ Interfaces (inbound)      │  │   REST controllers,
                     │  │ Application / orchestration│ │   scheduling entry points
                     │  │ Domain                    │  │
                     │  │ Ports (interfaces)        │  │
                     │  └──────────────────────────┘  │
                     │              │                 │
                     │  ┌──────────────────────────┐  │
                     │  │ Infrastructure (adapters) │  │
                     │  │  ├─ PostgreSQL / pgvector │──┼──▶ PostgreSQL + pgvector
                     │  │  ├─ Source connectors     │──┼──▶ configured sources
                     │  │  └─ Python AI service     │──┼──┐
                     │  └──────────────────────────┘  │  │
                     └───────────────────────────────┘  │
                                                          │  HTTP/JSON
                                                          │  (versioned contracts)
                                                          ▼
                                          ┌───────────────────────────┐
                                          │   Python AI Service       │
                                          │   (FastAPI, stateless)    │
                                          │   AI capabilities         │
                                          └─────────────┬─────────────┘
                                                          │  LLM provider abstraction
                                                          ▼
                                          ┌───────────────────────────┐
                                          │  LLM / embedding runtime  │
                                          │  (Ollama initially;       │
                                          │   replaceable)            │
                                          └───────────────────────────┘
```

No infrastructure beyond this diagram is part of the MVP: no message broker, no
service mesh, no separate vector database, no cache tier, no orchestration
platform (`docs/03-technical-spec.md` Section 25; Section 16 below).

---

## 3. Main Runtime Components

For each component: **responsibility**, **what it owns**, **what it must not
own**, and **how it communicates**. This restates and organizes
`docs/03-technical-spec.md` Section 5.2 architecturally; it does not change it.

### 3.1 React frontend

- **Responsibility:** present signals, search, question answering, and
  configuration screens to the user; collect user input (source/interest
  configuration, feedback).
- **Owns:** presentation state, the UI, the generated API client.
- **Must not own:** any business data, any database connection, business rules
  duplicated from the backend.
- **Communicates:** exclusively via REST/JSON with the Java backend's
  `/api/v1` (Section 6; `docs/03-technical-spec.md` Section 12).

### 3.2 Java backend (Spring Boot)

- **Responsibility:** the single owner of business state and behavior — API,
  orchestration of the ingestion/processing/signal pipeline, scheduling,
  persistence, provenance integrity, and the deterministic parts of the signal
  decision.
- **Owns:** business state and transactions, processing lifecycle, scheduling,
  the API surface, the composition of ports to adapters.
- **Must not own:** semantic judgement or model inference (that is delegated to
  the Python AI service) and does not itself run the LLM.
- **Communicates:** REST/JSON inbound (from the frontend); JDBC to PostgreSQL;
  synchronous HTTP/JSON outbound to the Python AI service and to configured
  sources.

### 3.3 Python AI service

- **Responsibility:** provide specialized, **stateless** AI/NLP capabilities
  (classification, relevance, importance assessment, summarization, embedding,
  answer synthesis, near-duplicate similarity) behind explicit versioned
  contracts (`docs/03-technical-spec.md` Section 7.2).
- **Owns:** the AI capability implementations, prompt management, the LLM
  provider abstraction on its side.
- **Must not own:** business persistence, orchestration, scheduling, or
  processing state (`docs/03-technical-spec.md` Section 7.4).
- **Communicates:** synchronous HTTP/JSON with the Java backend (request in,
  typed result or typed error out); HTTP to the configured LLM/embedding
  runtime through its own provider abstraction.

### 3.4 PostgreSQL

- **Responsibility:** the single system of record for business data and for
  vector embeddings.
- **Owns:** sources, interests, raw items, processing state, relevant
  information, signals, summaries, feedback, activity records, and embeddings
  (`docs/03-technical-spec.md` Section 11.1).
- **Must not own:** business rules or orchestration logic (those live in the
  Java application/domain layers, not in stored procedures or triggers beyond
  what Flyway migrations define).
- **Communicates:** JDBC, reachable only from the Java backend.

### 3.5 pgvector (inside PostgreSQL)

- **Responsibility:** store embeddings and serve nearest-neighbor queries for
  semantic search and retrieval, as an extension of PostgreSQL — not a separate
  service (`docs/03-technical-spec.md` Section 4.3, 11.2).
- **Owns:** vector columns and their ANN indexes, transactionally consistent
  with the rows they describe.
- **Must not own:** anything outside the single PostgreSQL instance; there is no
  second vector store.
- **Communicates:** through the same JDBC connection the backend already uses;
  retrieval queries are issued by the Java backend (`docs/03-technical-spec.md`
  Section 9.8: Java performs retrieval, Python synthesizes).

### 3.6 Configured source connectors

- **Responsibility:** fetch raw information from a configured, curated source
  (`docs/01-product-spec.md` Section 8; `docs/02-functional-spec.md` Section 4).
- **Owns:** the fetch/parse mechanics for one **source type**, behind the
  `SourceCollector` port (`docs/03-technical-spec.md` Section 10.2).
- **Must not own:** normalization, deduplication, relevance/importance
  assessment, or persistence — those are pipeline stages downstream of
  collection (Section 8 below).
- **Communicates:** outbound HTTP(S) to the external source, inbound to the Java
  backend's ingestion pipeline as raw payloads.

### 3.7 Local/replaceable LLM provider

- **Responsibility:** perform model inference (chat/structured-output
  generation and embeddings) for the Python AI service's capabilities and, where
  applicable, for the Java-side LLM ports.
- **Owns:** nothing Signal-Engine-specific; it is an external, replaceable
  runtime (`docs/03-technical-spec.md` Section 5.2).
- **Must not own:** business logic, provenance, or persistence.
- **Communicates:** through the LLM provider abstraction only (Section 9 below);
  initially **Ollama** running `gpt-oss:20b`, never referenced by name outside
  configuration and the provider adapter (`docs/03-technical-spec.md`
  Section 3.6, 9.1).

---

## 4. Java Backend Architecture

The Java backend follows **Clean Architecture / Ports and Adapters**
(`docs/03-technical-spec.md` Sections 3.1, 3.2, 6.1). Four conceptual layers:

```
        ┌───────────────────────────────────────────────┐
        │  Interfaces (inbound adapters)                 │
        │  REST controllers, DTOs, request validation,   │
        │  scheduling entry points                       │
        └───────────────────┬─────────────────────────────┘
                             │  calls into
                             ▼
        ┌───────────────────────────────────────────────┐
        │  Application (use cases / orchestration)       │
        │  defines PORTS (interfaces) for everything      │
        │  external; owns transaction boundaries          │
        └───────────────────┬─────────────────────────────┘
                             │  depends on
                             ▼
        ┌───────────────────────────────────────────────┐
        │  Domain                                        │
        │  entities, value objects, domain services,      │
        │  domain errors — deterministic business rules   │
        └─────────────────────────────────────────────────┘
                             ▲
                             │  implements the ports declared above
        ┌───────────────────┴─────────────────────────────┐
        │  Infrastructure (outbound adapters)             │
        │  Spring Data JDBC repositories, Flyway,          │
        │  the Python agent client, the LLM provider       │
        │  adapter (Spring AI, isolated), source-connector │
        │  implementations, telemetry                      │
        └───────────────────────────────────────────────┘
```

### 4.1 Dependency direction

`interfaces → application → domain` and `infrastructure → application →
domain`. Dependencies point **inward only**; nothing depends outward
(`docs/03-technical-spec.md` Section 6.1). Concretely:

- **The domain does not depend on Spring.** No Spring annotation, no Spring
  type, appears in domain code.
- **The domain does not depend on PostgreSQL.** No JDBC type, no SQL, no Spring
  Data type appears in domain code.
- **The domain does not depend on HTTP.** No HTTP client, no servlet type, no
  REST framework type appears in domain code.
- **The domain does not depend on any LLM provider.** No Spring AI type, no
  provider SDK, no provider or model name (`ollama`, `gpt-oss:20b`) appears in
  domain (or application) code (`docs/03-technical-spec.md` Section 3.6).

### 4.2 What each layer does

- **Application** declares the **ports** it needs (repositories, AI
  capabilities, source collectors, clock, id generation) and coordinates use
  cases across them; it owns transaction boundaries and does not itself talk to
  a database, an HTTP client, or an LLM (`docs/03-technical-spec.md`
  Section 3.2, 6.4).
- **Infrastructure** implements those ports: Spring Data JDBC repositories,
  Flyway-managed schema, the HTTP client that calls the Python AI service, the
  Spring-AI-backed adapter for the Java-side LLM ports, and one adapter per
  source type.
- **Spring is a wiring and infrastructure concern.** It is used for dependency
  injection, transaction management, scheduling, and the REST layer — all in
  the interfaces and infrastructure layers, never in the domain
  (`docs/03-technical-spec.md` Section 3.1).
- **The composition root** (a single Spring configuration area) wires port
  implementations from configuration, so provider and connector selection stays
  out of business code (`docs/03-technical-spec.md` Section 6.2).

### 4.3 Orchestration

The backend runs the ingestion → processing → signal pipeline as a sequence of
**item-scoped stages**, each a use case with its own transaction and its own
persisted outcome; a scheduler triggers collection and drives pending work
(`docs/03-technical-spec.md` Section 6.3, 6.5). This orchestration is entirely
an **application-layer** responsibility — the domain expresses the rules, the
application sequences them.

No package layout is fixed here beyond the four conceptual layers above; exact
package names are an implementation detail, not an architectural decision.

---

## 5. Python AI Service Architecture

The Python AI service is architected **independently** from the Java backend.
It is **not** a Python mirror of Spring's architecture
(`docs/03-technical-spec.md` Section 3.1, 3.2, 7.1).

### 5.1 Structure

```
FastAPI                          — HTTP boundary; request/response models
   │                               (Pydantic); Depends used only for
   │                               HTTP-bound, request-scoped concerns
   ▼
Application capability           — a plain function or small class per
   │                               capability (classify, assess-relevance,
   │                               assess-importance, summarize, embed,
   │                               answer, similarity)
   ▼
Native Python composition /      — explicit construction at the app's
lightweight Protocols             composition entry point; typing.Protocol
   │                               or abc.ABC only where it earns its keep
   ▼
Concrete implementation          — the LLM provider client, prompt loader,
                                    embedding client, …
```

This mirrors the conceptual shape of `docs/03-technical-spec.md` Section 7.1,
which is authoritative; this document only restates it at the architecture
level.

### 5.2 Dependency composition rules

- Dependencies are **composed explicitly at the application's composition entry
  point** — the module that builds the FastAPI app — by constructing concrete
  objects and passing them into capability handlers as plain arguments. There is
  no hidden registry and no service locator.
- **`typing.Protocol`** (or, where inheritance genuinely helps, `abc.ABC`) is
  used **only when it provides a concrete benefit**: replaceability (e.g.
  swapping the LLM client), testability (faking a dependency), or a clear
  separation of responsibilities. It is not added because Spring would express
  the same idea as an interface, a `@Service`, or a `@Component`.
- **No Python dependency-injection container is used**, and none is required for
  the MVP. No DI/IoC framework, and no other Python framework, is adopted merely
  to reproduce Spring concepts.
- **FastAPI's `Depends`** may be used for HTTP-bound, request-scoped concerns
  (reading a header, validating a request, providing a request-scoped client),
  but it is **not** the general application architecture. Capability logic is
  plain Python, composed and testable independently of FastAPI.

### 5.3 Provider abstraction on the Python side

The same conceptual LLM provider port described in Section 9 exists on the
Python side as a plain Python object behind a lightweight `typing.Protocol`,
composed natively — not implemented with Spring AI and not resolved through a
container (`docs/03-technical-spec.md` Section 9.2).

### 5.4 Structured outputs, validation, and bounded repair

- Capabilities that feed business logic request **structured output** with an
  explicit schema; the response is validated against that schema before being
  returned to Java (`docs/03-technical-spec.md` Section 9.4, 9.6).
- On a schema-invalid model output, one **bounded repair attempt** re-prompts
  with the validation error; on repeated failure the capability returns a typed
  error rather than a fabricated result (`docs/03-technical-spec.md`
  Section 9.6). The exact repair-attempt count is fixed in
  `docs/03-technical-spec.md` Section 13.3, not repeated here.

### 5.5 What the Python service remains

Unchanged from `docs/03-technical-spec.md` Section 7:

- **Stateless** — each capability is a pure function of its request plus the
  configured model(s); no state is held between requests.
- **No business database ownership** — no writes to business tables, no direct
  connection to the business schema.
- **No scheduling responsibility** — the Java backend schedules; Python only
  responds to calls.
- **No product/business orchestration** — Python does not decide what happens
  next in the pipeline; it answers one capability request and returns.
- **No "God agent"** — each capability keeps one responsibility; the service may
  later be split into several deployables without changing any contract
  (Section 11 below).

---

## 6. Communication Boundaries

| Boundary | Mechanism | Responsibility of the boundary |
|----------|-----------|--------------------------------|
| Browser → Java backend | Synchronous REST/JSON, `/api/v1`, described by OpenAPI | The only way the frontend reaches the system; carries user actions and returns view data |
| Java backend → Python AI service | Synchronous HTTP/JSON, **versioned** request/response contracts (`RelevanceRequest → RelevanceResponse`, etc.) | Explicit, schema-validated capability calls; the backend never assumes Python's internals |
| Java backend → PostgreSQL | JDBC, within backend-owned transactions | The backend is the **only** writer of business tables; a Python or LLM call is never executed inside an open database transaction |
| Java backend → source systems | Outbound HTTP(S) via a per-source-type collector adapter | Fetches raw content only; no business decision is made at this boundary |
| Python AI service → LLM/embedding provider | HTTP, through the Python-side provider abstraction (Section 5.3) | Model inference only; the provider name never leaks past this boundary |

All of the above are **synchronous request/response**. Preserving the approved
MVP choice (`docs/03-technical-spec.md` Section 5.4, 8.1, 25):

- No message broker, no event bus.
- No unnecessary asynchronous infrastructure — the internal pipeline is driven
  by a scheduler and persisted processing state (Section 8 below), not a queue.
- Every cross-language contract is **explicit and versioned**
  (`docs/03-technical-spec.md` Section 8), so either side can change internally
  without breaking the other.

---

## 7. Ingestion Architecture

The approved high-level flow (`docs/03-technical-spec.md` Section 10.1;
`docs/02-functional-spec.md` Section 3):

```
Source
  → Collection
    → Normalization
      → Deduplication
        → Relevance
          → Importance / signal decision
            → Summary
              → Persistence / signal presentation
```

### 7.1 Deterministic vs AI responsibilities

Following `docs/03-technical-spec.md` Section 3.3 ("deterministic software for
deterministic problems, AI for semantic problems"):

| Stage | Nature | Where |
|-------|--------|-------|
| Collection (fetch, parse) | Deterministic | Java (per-source-type connector) |
| Normalization, content extraction | Deterministic | Java |
| Duplicate detection (identity/hash match) | Deterministic | Java |
| Near-duplicate detection | Semantic (embeddings) + a deterministic threshold decision | Python capability + Java |
| Classification (area of interest), relevance assessment | Semantic | Python (LLM) |
| Importance assessment | Semantic | Python (LLM) |
| Signal state transition (create signal, set state, decide whether to alert) | Deterministic | Java |
| Summarization | Semantic | Python (LLM) |
| Persistence | Deterministic | Java |

The **assessment** of importance is semantic and produced by Python; the
**state transition** that acts on that assessment is deterministic and owned by
Java (`docs/03-technical-spec.md` Section 3.3). The selection criteria
themselves — what counts as "important enough" — are an open product question
(`docs/02-functional-spec.md` Q4) and are not defined by this document or by
`docs/03-technical-spec.md`.

### 7.2 Source connectors

Adding or replacing a source is configuration; adding a new **source type**
means implementing the `SourceCollector` port once, with no change to
normalization, deduplication, AI processing, signals, search, or alerting
(`docs/03-technical-spec.md` Section 10.2, 21.1–21.2). Concrete source
connectors are not designed here — see `docs/08-ingestion.md`.

---

## 8. AI Processing Boundary

### 8.1 Where AI is used

Classification, relevance assessment, importance assessment, summarization,
embedding generation, near-duplicate similarity scoring, and answer synthesis
are AI capabilities, delegated to the Python AI service
(`docs/03-technical-spec.md` Section 3.3, 7.2).

### 8.2 Where AI is deliberately not used

- Whether a source is enabled, whether an item was already seen (identity/hash
  match), whether a retry is due, whether a Python response is schema-valid —
  all deterministic, all in Java (`docs/03-technical-spec.md` Section 3.3).
- The **final business state transition** — creating a signal, setting its
  state, deciding whether to alert — is always deterministic Java code acting on
  an AI assessment, never AI code acting directly on business state.

### 8.3 AI output is treated as untrusted input

- AI output that feeds business logic is always **structured** (an explicit
  schema), always **schema-validated** before it is used, and always
  **bounded** — a fixed, small number of repair attempts before the item is
  marked failed rather than looped indefinitely
  (`docs/03-technical-spec.md` Section 3.4, 9.6, 13.5).
- A schema-invalid Python response is a non-retryable contract violation for
  that item, not something the backend tries to interpret
  (`docs/03-technical-spec.md` Section 8.3).
- **AI output is never allowed to directly mutate business state.** The Java
  backend is the only writer of business tables; an AI capability returns a
  result, and a Java use case decides what, if anything, happens to persisted
  state as a consequence (`docs/03-technical-spec.md` Section 6.4, 7.4).
- AI-generated content keeps facts drawn from the source distinguishable from
  interpretation added by the model, and is never framed as advice
  (`docs/02-functional-spec.md` R4, R25; `docs/03-technical-spec.md`
  Section 3.4).

---

## 9. Persistence and Data Ownership

- **PostgreSQL is the single system of record.** It stores sources, interests,
  raw items, processing state, relevant information, signals, summaries,
  feedback, activity records, and embeddings
  (`docs/03-technical-spec.md` Section 11.1).
- **pgvector** provides vector storage and nearest-neighbor search **inside**
  PostgreSQL — not a separate database (`docs/03-technical-spec.md`
  Section 4.3, 11.2).
- **The Java backend owns persistence and all business state**, including all
  transactions. It is the only component with a database connection to business
  tables.
- **Python does not own business tables.** It has no direct connection to the
  business schema; when a capability needs retrieved context (for example,
  answer synthesis), Java performs the pgvector query and passes the retrieved
  passages to Python (`docs/03-technical-spec.md` Section 7.4, 9.8).
- **No second vector database** exists or is planned for the MVP
  (`docs/03-technical-spec.md` Section 25).
- **No component other than the Java backend accesses PostgreSQL** — in
  particular, the frontend never connects to the database directly
  (`CLAUDE.md` Section 14; `docs/03-technical-spec.md` Section 22, item 5).

The complete schema (tables, columns, indexes, constraints) is not defined
here — see `docs/05-data-model.md`.

---

## 10. Frontend Architecture

- **UI:** a React single-page application; presentation and user interaction
  only.
- **API client:** a **typed client generated from the OpenAPI document**
  published by the Java backend, so the frontend stays in sync with the backend
  contract (`docs/03-technical-spec.md` Section 12.2). This is the already
  approved direction — this document does not change it.
- **Presentation state:** local component/UI state (selected filters, open
  panels, in-progress form input) and server data fetched through the generated
  client. **No state-management library** (Redux, MobX, Zustand, or similar) is
  introduced for the MVP; React's own state and simple data-fetching against the
  API client are sufficient (`docs/03-technical-spec.md` Section 4.14).
- **Backend communication:** REST/JSON against `/api/v1` only, described in
  Section 6 above.
- **No direct database access.** The frontend never reaches PostgreSQL.
- **No duplicated business logic.** Relevance, importance, deduplication,
  signal-state rules, and provenance handling live in the Java backend; the
  frontend renders what the backend returns and does not re-implement these
  rules.

Individual screens, components, and their layouts are not designed here.

---

## 11. Replaceability and Plug-and-Play Boundaries

Using only the mechanisms already approved in `docs/03-technical-spec.md`
(Section 21):

| Replace... | Mechanism | Effect on the rest of the system |
|------------|-----------|-----------------------------------|
| A **source** | Change configuration (name, reference, type) | None — no code change if the source type already has a connector |
| A **source type** (add new) | Implement the `SourceCollector` port once, register it | No change to normalization, dedup, AI processing, signals, search, or alerting |
| The **LLM provider** | Implement the provider adapter for the `LlmChatProvider` / `EmbeddingProvider` port (Java side and/or Python side, per Section 5.3, Section 9) | No change to capability services, application logic, or the domain; business code never branches on a provider name |
| The **embedding provider/model** | Same provider-abstraction mechanism as the LLM provider | A model that changes vector dimension requires a documented re-embedding step (`07-rag.md`); no architectural change |
| An **AI capability implementation** | Change the concrete implementation behind the capability's contract | The contract (request/response shape) is what the Java side depends on, not the implementation |
| The **model** used by a capability | Configuration only (provider id / model id / params / prompt version) | Different capabilities may use different models with no architectural change |
| **Future Python service decomposition** | Split the single Python service into several deployables, routed per capability by configuration | Contracts are unchanged; this is an option, not an MVP requirement (`docs/03-technical-spec.md` Section 21.6) |

No plugin framework, service registry, or dynamic-loading mechanism is
introduced to achieve this — replaceability comes from the port/adapter
boundary and from configuration, which is already sufficient
(`docs/03-technical-spec.md` Section 21).

---

## 12. Processing State and Idempotency

- **Explicit processing states** are persisted for every raw item, so its
  position in the pipeline is always known
  (`docs/03-technical-spec.md` Section 3.5, 10.3). State is written before and
  after each stage.
- **Stage boundaries** are the same boundaries described in Section 7: each
  pipeline stage is a distinct, persisted step with a defined input and output.
- **Idempotent processing:** each raw item has a deterministic identity (its
  source-provided id, plus a content hash); collection upserts by that identity,
  and each stage's output is written by upsert keyed to the item and stage, so
  re-running a stage converges to one correct result rather than duplicating
  work (`docs/03-technical-spec.md` Section 10.4).
- **Retries** apply only to retryable failures and are bounded; a failure in one
  item, source, or AI step never blocks others
  (`docs/03-technical-spec.md` Section 3.5, 13.2, 13.6). Exact retry counts and
  backoff parameters are fixed in `docs/03-technical-spec.md` Section 13.3 as
  proposed defaults, not repeated or re-decided here.
- **Resumability:** because state is persisted at every stage and stages are
  idempotent, processing can resume from where it left off after a restart or a
  transient failure, without reprocessing already-completed stages.

Detailed per-stage behavior belongs to `docs/08-ingestion.md`; this section only
fixes the architectural role of state and idempotency.

---

## 13. Error and Failure Boundaries

Failures are contained at the same boundaries described in Section 6, so that a
failure in one component is visible and does not silently propagate as
incorrect behavior in another (`docs/03-technical-spec.md` Section 13):

- **Source failure:** contained at the collector boundary. A source that cannot
  be fetched or parsed fails only its own collection; other sources are
  unaffected, and the failure is recorded per source.
- **Python AI service failure (unreachable, error response, timeout):**
  contained at the Java↔Python HTTP boundary. Transport/timeout/5xx failures are
  retryable (bounded); the affected item is marked pending or failed, and the
  pipeline continues for other items.
- **LLM/provider failure:** contained inside the Python capability. A model
  timeout or an unrecoverable malformed output becomes a typed error returned to
  Java, never a fabricated result.
- **Validation failure (bad request, contract violation):** non-retryable,
  reported at the boundary where the invalid data was received (the API layer
  for user input; the Java-side contract validator for a schema-invalid Python
  response).
- **Database failure:** contained at the persistence boundary; the backend
  reports it rather than silently losing the write, and does not call Python or
  the LLM from inside an open transaction.
- **Frontend/API failure:** surfaced to the user as a clear, correlated error;
  the frontend never fabricates data to mask a missing or failed backend
  response.

The full error taxonomy (domain / application / validation / infrastructure /
external source / AI-provider / timeout), the retryable-vs-non-retryable rule,
and the proposed retry/backoff/timeout defaults are defined once, in
`docs/03-technical-spec.md` Section 13, and are not restated or altered here.

---

## 14. Observability Boundary

The approved MVP approach (`docs/03-technical-spec.md` Section 14):

- **Structured logs**, carrying a correlation/processing id, component,
  operation, and outcome for pipeline work.
- **Correlation IDs** propagate across every boundary in Section 6: from the API
  request or the raw item, through every pipeline stage, through the Java→Python
  call, into the Python logs.
- **OpenTelemetry tracing** connects a span for the Java pipeline stage to a
  child span for the Python capability call to a child span for the LLM provider
  call, so the path is traceable end to end.
- **Minimal metrics** (counts and durations for collection, processing outcomes,
  Python/LLM call latency and failure rates) are exposed, not aggregated into a
  hosted stack.
- **No dedicated observability dashboard or platform is part of the MVP.**
  Collector, storage, dashboards, and alerting on telemetry are an operational
  evolution (`docs/01-product-spec.md` Section 8; `docs/03-technical-spec.md`
  Section 14.5, 25), not an MVP architectural component.

---

## 15. Security Boundaries

Only the architecture-level security concerns already approved
(`docs/03-technical-spec.md` Section 20):

- **No authentication in the MVP.** The system has one user and no login; the
  API is structured so an authentication layer can be added at the inbound
  boundary later without touching the application or domain layers
  (`docs/02-functional-spec.md` R14; `docs/03-technical-spec.md` Section 21.7).
- **Localhost-oriented MVP.** The backend and the Python AI service bind to
  localhost by default; PostgreSQL is not exposed outside the local Compose
  network.
- **SSRF protection for source fetching.** Because Signal Engine fetches remote
  content, the collector boundary (Section 7.2) is the point where scheme
  restrictions, size limits, redirect limits, and blocking of
  private/loopback/link-local address ranges are enforced. The concrete policy
  is specified with the connectors (`docs/08-ingestion.md`); this document only
  fixes that the boundary is where this protection belongs.
- **Hostile/untrusted source content.** Fetched content is parsed as
  potentially hostile input at the same boundary — no active content execution,
  bounded parsing.
- **Secrets/configuration boundary.** Secrets (database credentials, future
  provider API keys) are read only through the configuration layer described in
  `docs/03-technical-spec.md` Section 15; they are never embedded in domain,
  application, or Python capability code, and never committed to source.
- **AI output as untrusted input** is also a security boundary, not only a
  correctness one — structured, schema-validated output prevents AI-generated
  text from being used unvalidated to build SQL, file paths, shell commands, or
  outbound requests (`docs/03-technical-spec.md` Section 20.1).
- **Supply-chain considerations:** dependency and container-image vulnerability
  scanning, pinned versions and lockfiles, and minimal base images are part of
  CI, not of the runtime architecture (`docs/03-technical-spec.md`
  Section 18.1, 20.3).

An authentication architecture is not designed here; it is explicitly deferred
(Section 18).

---

## 16. Deployment Topology

The approved MVP topology is a single Docker Compose stack:

```
Docker Compose
 ├── PostgreSQL (+ pgvector extension)
 ├── Java backend (Spring Boot)
 ├── Python AI service (FastAPI)
 ├── React frontend (dev server, or static assets served by the backend)
 └── Ollama (started by default; a host install remains a supported alternative)
```

This matches `docs/03-technical-spec.md` Section 5.3 and Section 17.2: a
**modular Java monolith + one Python AI sidecar**, not a distributed system.

The architecture **intentionally avoids**, for the MVP
(`docs/03-technical-spec.md` Section 25):

- Kubernetes, a service mesh, or an API gateway.
- A message broker or an event bus.
- Redis or any dedicated cache tier.
- A separate vector database.
- A workflow orchestration platform.
- Distributed scheduling or multi-node coordination — the backend runs as a
  single instance.

Any of these would be justified only by a concrete requirement that does not
currently exist (`docs/03-technical-spec.md` Section 2, item 9; Section 3.8).

---

## 17. Architectural Constraints

Summarized from `docs/03-technical-spec.md` Section 22, restated here because
they are structural, not incidental:

1. **Domain independence:** the domain layer has no dependency on Spring, JPA,
   HTTP, the LLM SDK, or any provider (Section 4.1).
2. **Python independence from Spring concepts:** Python composes dependencies
   natively; no Spring-style service/component layer, no DI container, no
   framework adopted to mirror a Java concept (Section 5.2).
3. **Java ownership of business state:** all business persistence and
   transactions are owned by the Java backend; Python holds no processing state
   between requests (Sections 3.3, 8, 9).
4. **No provider leakage:** no provider or model name appears outside
   configuration and the provider adapters, on either side (Section 4.1, 5.3,
   Section 9).
5. **No frontend database access:** the frontend reaches the system only through
   `/api/v1` (Section 10).
6. **No LLM calls inside database transactions:** the backend commits state,
   calls Python, then commits the result of that call — a slow or failing AI
   call never holds a database transaction open
   (`docs/03-technical-spec.md` Section 6.4).
7. **Schema validation of AI output:** every Python response is validated
   against its contract schema before it enters business logic (Section 8.3).
8. **Configuration over hardcoding:** every environment-specific value,
   including provider and model selection, is configuration, not code
   (`docs/03-technical-spec.md` Section 3.7, 15).
9. **No unnecessary infrastructure:** no message broker, second database,
   separate vector store, orchestration platform, or cache tier in the MVP
   (Section 16).
10. **One backend instance for the MVP:** no multi-node coordination is assumed
    or required (`docs/03-technical-spec.md` Section 6.5, 22 item 11).

---

## 18. Evolution Path

The following are **already-approved future directions**, not part of the MVP
architecture described above. They are listed so the architecture's
replaceability boundaries (Section 11) can be exercised later without a
redesign.

- **Additional source adapters** — new `SourceCollector` implementations for new
  source types, added without touching downstream processing
  (`docs/03-technical-spec.md` Section 21.2).
- **Replacing LLM providers** — a new provider adapter behind the existing
  provider port, on the Java side and/or the Python side
  (`docs/03-technical-spec.md` Section 21.3).
- **Replacing models** — configuration-only change per capability
  (`docs/03-technical-spec.md` Section 21.4).
- **Additional AI capabilities** — a new versioned contract plus a new Python
  handler and (optionally) a new Java port, without disturbing existing
  capabilities (`docs/03-technical-spec.md` Section 21.5).
- **Future Python service split** — decomposing the single Python service into
  several deployables by capability, routed by configuration, with contracts
  unchanged (`docs/03-technical-spec.md` Section 21.6).
- **Future authentication boundary** — introduced at the inbound adapter layer
  plus a user concept, without changing application or domain code for existing
  single-user behavior (`docs/03-technical-spec.md` Section 21.7).
- **Future multilingual support** — additional source languages processed
  through the same pipeline once the product decides which languages and how
  (`docs/02-functional-spec.md` Q24 (future); `docs/03-technical-spec.md`
  Section 15.3, Section 24 T19).
- **Future PostgreSQL upgrade** — moving from PostgreSQL 17 to 18 once tooling
  (notably Flyway) certifies it; a version bump, not a schema or architecture
  change (`docs/03-technical-spec.md` Section 24, T20).

Each of these is explicitly **evolution**, not a hidden part of the current
architecture — none of it is built now.

---

## 19. Open Architectural Questions

This document does not resolve any open question. Where the architecture
described above depends on one, it is named here, with its source:

- **Frontend serving topology** — whether the frontend is served as static
  assets by the backend or from its own container (`docs/03-technical-spec.md`
  Section 12.2, Section 24 T8).
- **Real-time update mechanism** — polling vs SSE/WebSocket for signal/activity
  updates in the UI; also depends on the still-open alert-delivery-channel
  product question (`docs/02-functional-spec.md` Q19;
  `docs/03-technical-spec.md` Section 12.3, Section 24 T13).
- **Python service split timing** — the mechanism exists (Section 11, Section
  18), but whether/when to actually split depends on hardware/model needs not
  yet known (`docs/03-technical-spec.md` Section 24 T12).
- **pgvector index details** — HNSW vs IVFFlat and their parameters, to be tuned
  once real data exists (`docs/03-technical-spec.md` Section 11.2, Section 24
  T7).
- **Embedding model and vector dimension** — not yet chosen; affects the
  re-embedding path described in Section 11
  (`docs/03-technical-spec.md` Section 9.3, Section 24 T3).
- **Retry/timeout finalization** — the proposed defaults in
  `docs/03-technical-spec.md` Section 13.3–13.4 are subject to tuning with real
  telemetry (`docs/03-technical-spec.md` Section 24 T9).

In addition, this architecture depends on — without resolving — the
still-open **product/functional** questions it inherits from
`docs/02-functional-spec.md` Section 17, most notably: the signal-selection
criteria (Q4), the near-duplicate threshold (Q12), the collection cadence (Q9),
the alert delivery channel (Q19), and activity-history retention (Q22). Where
this document describes a mechanism that depends on one of these (for example,
the deterministic threshold in Section 7.1, or the retention-ready schema
implied by Section 9), the mechanism is fixed but the value or policy is not.

---

## 20. Architecture Summary

Signal Engine's MVP is a **modular Java backend** (Clean Architecture / Ports
and Adapters — domain, application, ports, infrastructure) that owns all
business state, orchestration, and persistence in **PostgreSQL with pgvector**;
a **single, stateless Python AI service** (FastAPI, natively composed, no Spring
DI mirror) that provides specialized AI capabilities behind explicit versioned
contracts; a **replaceable LLM/embedding provider** reached only through a
provider abstraction on both the Java and Python sides; a set of **plug-and-play
source connectors**; and a **React frontend** that talks to the system only
through the backend's REST API.

The architecture's defining boundaries are: Java owns business state and
orchestration; Python is stateless and specialized; deterministic rules and
semantic (AI) judgement are explicitly separated, with AI output always
structured, validated, bounded, and unable to mutate business state directly;
communication everywhere is synchronous HTTP/JSON over versioned contracts, with
no message broker or other unnecessary infrastructure; and every component
named above — sources, the LLM provider, the embedding model, individual AI
capabilities, and eventually the Python service itself — is replaceable through
configuration and adapters, not through a redesign.

Nothing in this document introduces a technology, pattern, or decision beyond
what `CLAUDE.md`, `docs/01-product-spec.md`, `docs/02-functional-spec.md`, and
`docs/03-technical-spec.md` already establish.
