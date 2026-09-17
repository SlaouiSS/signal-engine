# Signal Engine — Technical Specification

Document ID: `03-technical-spec.md`
Status: Accepted — reviewed as part of the Phase 0 documentation baseline
(`docs/11-roadmap.md` Section 3); decisions this document marks open or
provisional remain open or provisional until resolved.
Depends on: `CLAUDE.md`, `docs/01-product-spec.md`, `docs/02-functional-spec.md`

Scope: This document defines **how** Signal Engine should be built technically —
the technology stack, the architecture, the boundaries between components, and
the cross-cutting technical concerns (error handling, logging, testing, CI/CD,
configuration, security).

It does **not**:

- redefine product scope (`docs/01-product-spec.md`) or functional behavior
  (`docs/02-functional-spec.md`);
- define the detailed software architecture and diagrams — that belongs to
  `docs/04-architecture.md`;
- define the database schema — that belongs to `docs/05-data-model.md`;
- define the AI agents in detail — that belongs to `docs/06-ai-agents.md`;
- define the RAG design in detail — that belongs to `docs/07-rag.md`;
- define ingestion connectors in detail — that belongs to `docs/08-ingestion.md`;
- resolve the open product/functional questions. Where a technical choice
  depends on one, the dependency is named and the question is preserved (Section
  24).

Where this document proposes a concrete value (a timeout, a retry count, a
library version policy), it is marked **proposed default** and is subject to
tuning or an explicit decision. It is not a settled requirement.

**Version verification date: 2026-09-04.** The versions in Section 4 and
Section 19 were checked against official release pages, project documentation,
Maven Central, PyPI, and the npm registry on that date. Exact patch versions are
pinned in the build files and lockfiles (Section 19.3), not in this document;
this document fixes the **major/minor line** and the compatibility reasoning.

---

## 1. Purpose and Scope

### 1.1 Purpose

Give contributors and reviewers a single, coherent description of the technical
shape of Signal Engine: what is built, in what language, with what boundaries,
and why. The document should let a new contributor understand the system well
enough to run it, navigate it, and extend it without private knowledge.

### 1.2 In technical scope

- The technology stack and the reason for each significant choice.
- The high-level system architecture and the responsibilities of each component.
- The Java backend architecture (layering, boundaries, dependency rules).
- The Python AI-agent architecture and its responsibilities.
- The contract and communication model between Java and Python.
- The AI/LLM abstraction and provider independence.
- The ingestion pipeline architecture at a technical level.
- Persistence and vector search technology.
- API and frontend integration technology.
- Error handling, resilience, logging, and basic observability.
- Configuration and secret management.
- Testing strategy.
- Local development (Docker) and CI/CD (GitHub Actions).
- Dependency strategy and a security baseline.
- Extensibility / plug-and-play mechanics.

### 1.3 Out of technical scope

- Detailed class/package design and architecture diagrams (`04-architecture.md`).
- Concrete database tables, columns, indexes, and constraints
  (`05-data-model.md`).
- Concrete agent prompts, decision rubrics, and per-agent contracts in full
  (`06-ai-agents.md`).
- Retrieval strategy, chunking, context construction (`07-rag.md`).
- Concrete source connectors and parsing rules (`08-ingestion.md`).
- The full AI evaluation subsystem and the full observability platform — both are
  explicitly deferred by `docs/01-product-spec.md` Section 8.
- Deployment topology beyond reproducible local development. Production
  deployment and container publishing are described as an evolution
  (Section 18).

---

## 2. Technical Objectives

Derived from `CLAUDE.md` (Sections 8–13, 19, 23, 24, 29) and the two prior specs:

1. **Clean boundaries.** Domain and application logic must not depend on
   frameworks, providers, or infrastructure details.
2. **Replaceability.** Sources, AI providers, AI models, and individual AI
   capabilities must be swappable without changing core business logic.
3. **Right tool for the problem.** Deterministic software for deterministic
   rules; AI for semantic reasoning. (Section 3, Section 9.)
4. **Java owns business state.** All business persistence and transactions are
   owned by the Java backend. Python agents are stateless specialized workers.
5. **Source-grounded AI.** Every AI-generated, user-facing output is traceable to
   the source content that supports it. LLM output consumed by business logic is
   structured and validated first.
6. **Understandable failure.** Processing state is explicit; failures are
   classified, logged with correlation, and visible to the user as required by
   `docs/02-functional-spec.md` Section 15.
7. **Reproducibility.** A contributor can build and run the whole system locally
   with a documented, minimal set of commands.
8. **Automated quality.** Build, tests, and quality checks run in CI on every
   pull request.
9. **No unnecessary infrastructure.** No message broker, no separate vector
   database, no orchestration platform, no cache tier — unless a concrete
   requirement later proves the need (Section 25).
10. **Open-source friendliness.** Conventional stack, conventional tooling,
    documented decisions, low barrier to a first contribution.

---

## 3. Architectural Principles

These principles are binding for the implementation. `04-architecture.md` will
express them concretely; this section fixes the intent.

### 3.1 Clean Architecture / Ports and Adapters

- Four conceptual concerns: **domain**, **application (use cases)**,
  **interfaces (inbound adapters)**, **infrastructure (outbound adapters)**.
- Dependencies point **inward**. Domain depends on nothing external. Application
  depends only on the domain and on **ports** (interfaces it defines).
  Infrastructure implements ports.
- Frameworks (Spring, HTTP clients, JPA, the LLM SDK) live in infrastructure and
  inbound adapters, never in the domain.
- This four-layer structure is the **Java backend's** architecture (Section 6).
  The Python agents are a separate ecosystem with their own native composition
  mechanism, not a mirror of this layering or of Spring's component model
  (Section 7.1).

### 3.2 Dependency inversion and SOLID

- The application layer declares the interfaces it needs (for example
  `SourceRepository`, `RelevanceAssessor`, `EmbeddingProvider`,
  `LlmChatProvider`). Infrastructure provides implementations, wired by Spring at
  the composition root. **This is a Java/Spring-side pattern**; the Python agents
  compose their dependencies natively and do not use a DI container
  (Section 7.1).
- Small classes, one responsibility, intention-revealing names
  (`CLAUDE.md` Section 8). No `Manager` / `Helper` / `Util` where a precise name
  exists.

### 3.3 Deterministic software for deterministic problems, AI for semantic problems

This is the central principle. A decision is deterministic when it can be
computed from data with a rule; it is semantic when it requires judgement about
meaning.

| Decision | Nature | Where it runs |
|----------|--------|---------------|
| Is this source enabled? | Deterministic | Java |
| Have we already seen this exact item (identity / hash match)? | Deterministic | Java |
| Is this content a byte-for-byte or hash duplicate? | Deterministic | Java |
| Should we retry after a timeout? | Deterministic | Java |
| Is this item within the collection window? | Deterministic | Java |
| Is a Python response schema-valid? | Deterministic | Java |
| Is this item a **near-duplicate** of an existing story? | Semantic (may use embeddings + threshold) | Python capability + deterministic threshold in Java |
| Which area(s) of interest does this item relate to? | Semantic | Python (LLM) |
| Is this item relevant to the user's interests? | Semantic | Python (LLM) |
| Is it important enough to become a signal? | Semantic assessment; final state transition deterministic | Python assesses, Java records/acts |
| Summarize this item, grounded in its content. | Semantic | Python (LLM) |
| Vector for semantic search. | Semantic (model) | Python (embedding model) |

**The signal decision**: the *assessment* ("is this important enough?") is
semantic and produced by a Python capability; the *state transition* (create a
signal, set its state, decide whether to alert) is deterministic and owned by
Java. The selection criteria themselves are an open product question
(`docs/02-functional-spec.md` Q4) — this document does not define a scoring
algorithm.

### 3.4 AI output discipline

- LLM output that feeds business logic must be **structured** (a defined schema),
  **validated** against that schema before use, and **rejected** cleanly on
  failure (Section 9.6).
- LLM output separates **facts drawn from the source** from **interpretation
  added by the model**, per `docs/02-functional-spec.md` R4.
- No user-facing AI output is framed as advice (`docs/02-functional-spec.md`
  R25).

### 3.5 Processing discipline

- **Explicit processing states** (Section 10.3), persisted, so any item's
  position in the pipeline is always known.
- **Idempotent stages**: re-processing an item must not create duplicates or
  double-count; persistence uses upserts keyed by a deterministic identity
  (Section 10.4).
- **Retry and timeout policies** are explicit and configurable, not implicit in
  code (Section 13).

### 3.6 No hidden coupling

- No component reaches around a boundary (for example, the frontend must not read
  PostgreSQL; Python must not write business tables; the domain must not import
  an HTTP client).
- No provider name (`ollama`, a future provider) appears in application or domain
  code. Provider selection is configuration resolved at the composition root.

### 3.7 Configuration over hardcoding; no secrets in source

- Every environment-specific value is configuration (Section 15).
- No secret (API key, DB password) is committed. Local development uses a
  non-secret default DB password documented as development-only.

### 3.8 Simplicity constraint

Architecture complexity must be traceable to a requirement in `CLAUDE.md`,
`01-product-spec.md`, or `02-functional-spec.md`. If it is not, it does not
belong in the MVP.

---

## 4. Technology Stack

Each choice states: **what**, **why**, **problem solved**, **fit**, **cost**.
Versions verified 2026-09-04 (see the note in the preamble).

### 4.1 Java 25 (LTS) + Spring Boot 4.1.x — main backend

- **What:** The backend service: API, orchestration, scheduling, persistence
  coordination, processing lifecycle, Java↔Python communication.
- **Why:** Agreed direction. Mature ecosystem for long-running services,
  transactions, scheduling, HTTP, validation, testing.
- **Problem solved:** A single place that owns business state and coordinates a
  multi-stage pipeline with transactional guarantees.
- **Fit:** Strong typing and explicit boundaries support Clean Architecture; a
  serious, conventional open-source backend.
- **Versions:**
  - **Spring Boot 4.1.x** (current stable line; 4.1.1 at verification date),
    built on **Spring Framework 7.0.x**. Spring Boot 3.5 reached open-source
    end of life on 2026-06-30, so a new project starts on the 4.x line — this is
    a correction of the earlier "Spring Boot 3.x" text, not a preference change.
  - **Java 25**, the current LTS (released 2025-09), for which Spring Boot 4.1
    provides first-class and native-image support. Spring Boot 4's hard minimum
    is Java 17; **Java 21 (LTS) is an acceptable fallback** if a contributor
    toolchain requires it. Java 25 is chosen for the longest support runway and
    modern language features (virtual threads, pattern matching).
  - Spring Boot does **not** designate LTS releases; each minor has a ~12-month
    OSS support window, so the project tracks the current Spring Boot minor and
    upgrades roughly every 6–12 months (Section 19.3).
- **Cost:** JVM footprint; Spring's breadth invites framework leakage into
  business code — mitigated by the layering rules in Section 6. A Spring Boot 4
  minor upgrade every 6–12 months is a maintenance commitment, handled through
  CI-gated dependency PRs.

### 4.2 PostgreSQL 17 — source of truth

- **What:** The single relational database. Stores sources, interests, raw items,
  processing state, relevant information, signals, summaries, alerts, feedback,
  activity records, and embeddings.
- **Why:** Agreed direction. One well-understood database covers relational data
  **and** vector search (via pgvector), avoiding a second datastore.
- **Problem solved:** Durable, transactional business state with strong
  consistency and mature tooling (backups, migrations, testing).
- **Version:** **PostgreSQL 17.x** (released 2024-09, community support through
  2029-11). Chosen over the newest major (**18.x**, released 2025-09)
  deliberately: 17 has
  full, verified support across Flyway, Testcontainers, the JDBC driver, and the
  official Docker images, whereas some tooling (notably Flyway) still emits
  "newer than tested" warnings against 18 at the verification date. Moving to 18
  is a low-risk future step once Flyway certifies it (Section 24, T20).
- **Cost:** Vertical-scaling ceiling; not a specialized vector engine. Acceptable
  for the MVP (Section 11, Section 25).

### 4.3 pgvector 0.8.x — vector search inside PostgreSQL

- **What:** PostgreSQL extension providing a `vector` column type and
  approximate-nearest-neighbor indexes (HNSW, IVFFlat).
- **Why:** Agreed direction: prefer pgvector over a separate vector database for
  the MVP.
- **Version:** **pgvector 0.8.x** (0.8.2 at verification date, which also carries
  the fix for CVE-2026-3172 in parallel HNSW index builds — the project
  recommends upgrading to it). Supports PostgreSQL 13–18, so it is compatible
  with the chosen PostgreSQL 17. The Docker image is either an official
  `pgvector/pgvector` image or a Postgres image with the extension installed via
  an init step.
- **Problem solved:** Semantic search and RAG retrieval without new
  infrastructure, transactional with the rest of the data.
- **Cost:** Index tuning is manual; very large corpora would eventually need a
  dedicated engine — explicitly out of scope now (Section 25).

### 4.4 Python 3.13 — specialized AI agents

- **What:** One or more Python services exposing AI/NLP capabilities
  (classification, relevance, importance assessment, summarization, embedding,
  answer synthesis, near-duplicate similarity).
- **Why:** Agreed direction. Python has the strongest ecosystem for LLM clients,
  embeddings, and text processing.
- **Version:** **Python 3.13.x**. The newest stable release is **3.14** (released
  2025-10), but 3.13 is the version with the broadest verified library support
  for a new service in 2026 (FastAPI, Pydantic, the OpenTelemetry SDK, and common
  AI/NLP libraries all target it fully), it is in active maintenance until 2029,
  and it is the conventional, contributor-friendly choice. Moving to 3.14 later
  is a routine upgrade once dependencies confirm support.
- **Problem solved:** Access to AI tooling without forcing it into the JVM.
- **Fit:** Capabilities are stateless request/response functions — a natural
  service boundary.
- **Cost:** A second runtime and language to build, test, and deploy; a
  cross-language contract to maintain (Section 8). Justified by the AI ecosystem
  gap.

### 4.5 FastAPI + Pydantic + Uvicorn — Python service framework

- **What:** HTTP framework (**FastAPI 0.11x–0.14x**, currently 0.141.x),
  schema/validation (**Pydantic v2**, currently 2.13.x), ASGI server
  (**Uvicorn 0.5x**, currently 0.52.x) for the Python agent service(s).
- **Why:** Pydantic v2 gives first-class schema validation for both inbound
  contracts and LLM structured output; FastAPI generates OpenAPI automatically,
  which the Java side uses for contract tests. All three target Python 3.13.
- **Problem solved:** Typed, validated request/response handling with minimal
  boilerplate.
- **Fit:** Matches the "explicit contracts" requirement (Section 8). This stack
  is confirmed as the right minimal choice; no change from the previous
  specification.
- **Cost:** FastAPI is still pre-1.0 (0.x) and can make small breaking changes
  between minors — pinned via the lockfile and covered by contract tests.

### 4.6 LLM runtime: Ollama + `gpt-oss:20b` (initial), behind an abstraction

- **What:** Local LLM runtime (**Ollama**) and initial model (`gpt-oss:20b`) for
  all LLM calls (classification, relevance, importance, summarization, answer
  synthesis) and a local embedding model for vectors.
- **Why:** Agreed direction. Local-first: no external dependency, no per-call
  cost, no data leaving the machine.
- **Problem solved:** Semantic reasoning and text generation without a cloud
  account.
- **Fit:** Personal, open-source, privacy-friendly MVP.
- **Note on structured output:** Ollama supports both a plain `json` mode and a
  **JSON-Schema-constrained** mode; the JSON-Schema mode is what Section 9.4
  relies on. Models with a built-in "thinking"/reasoning trace can leak that
  trace into the response and break schema binding — the default model choice
  and the per-capability configuration avoid such models or use the prompt-based
  fallback (Section 9.6).
- **Cost:** Local inference is slower and quality-bounded by local model size.
  The architecture must not assume Ollama or this model (Section 9). The
  embedding model choice is a **proposed default / open** item (Section 24, T3).

### 4.7 Spring AI 2.0.x — Java-side LLM client, isolated behind our ports

- **What:** Spring's library for chat models, embeddings, and structured output,
  with a maintained Ollama integration.
- **Version / status:** **Spring AI 2.0.x** reached GA on 2026-06-12 (2.0.1 at
  verification date) and is built for **Spring Boot 4.1 / Spring Framework 7** —
  it aligns with this project's Java baseline. (The 1.1.x line targets Spring
  Boot 3.5 and is therefore not applicable.) This changes the earlier
  "candidate / proposed" status: a **stable, Boot-4-compatible release now
  exists**, so Spring AI is **adopted** as the implementation of the Java-side
  LLM ports.
- **Why appropriate:** It provides a provider-neutral chat/embedding client,
  JSON-Schema structured-output binding (`BeanOutputConverter`), self-correcting
  structured output, and an Ollama adapter — most of Section 9's Java-side needs,
  without hand-written HTTP and schema plumbing.
- **Boundary rule (unchanged):** Spring AI types **never** appear in the domain
  or application layers. It is wrapped behind Signal Engine's own ports
  (`LlmChatProvider`, `EmbeddingProvider`) in the infrastructure layer, so a
  future provider or a switch away from Spring AI is an adapter change only.
  Provider independence is preserved.
- **Cost:** Spring AI 2.0 made API changes versus 1.x; tying to it means tracking
  its releases in step with Spring Boot. Contained by the port boundary and by
  the fact that most LLM calls originate from the Python agents (Section 7).

### 4.8 Flyway — database migrations

- **What:** Versioned, forward-only SQL migrations applied on backend startup or
  via CI.
- **Version:** the **current stable Flyway Community edition** (pinned exactly in
  the Gradle version catalog; the Community edition's latest release at the
  verification date). Plain SQL migrations only — no Flyway feature that requires
  the paid tiers. Flyway's PostgreSQL support is fully verified for PostgreSQL 17
  (Section 4.2), whereas PostgreSQL 18 still triggers "newer than tested"
  warnings from Flyway at the verification date — one of the reasons for the
  PostgreSQL 17 choice.
- **Why:** Agreed direction. Deterministic, reviewable schema evolution.
- **Problem solved:** Reproducible schema across contributors and environments.
- **Cost:** Migration discipline (never edit an applied migration). Minimal.

### 4.9 Testcontainers — integration testing against real PostgreSQL

- **What:** Spins up a real PostgreSQL (with pgvector) in Docker for integration
  tests, wired through Spring Boot's `spring-boot-testcontainers` module and
  `@ServiceConnection`.
- **Version:** Spring Boot 4.x tracks the **Testcontainers 2.x** line; the
  Testcontainers version is taken from the Spring Boot dependency management
  (Section 19.3). A singleton-container base class is used for speed.
- **Why:** Agreed direction. Tests run against the real database engine and real
  SQL, including vector queries and the real Flyway migrations.
- **Cost:** Requires Docker in CI and locally; slower than unit tests — run in a
  separate CI job (Section 18). The Spring Boot 4 / Testcontainers 2.x
  combination is still maturing; the PostgreSQL module (the only one this project
  needs) is stable, but new modules should be adopted cautiously
  (Section 24, T21).

### 4.10 Docker + Docker Compose — reproducible local development

- **What:** Container images for backend, Python agents, PostgreSQL+pgvector, and
  (optionally) a local Ollama; a Compose file to run the whole system.
- **Why:** Agreed direction. One command to a working system.
- **Problem solved:** Onboarding friction; environment parity.
- **Fit:** Standard for open-source projects.
- **Cost:** Contributors need Docker; image maintenance. Ollama in Compose is
  **optional** because model downloads are large — a documented "use a host
  Ollama" path is provided (Section 17).

### 4.11 GitHub Actions — CI/CD

- **What:** Pipelines for pull-request validation, main-branch validation, and
  releases.
- **Why:** Agreed direction. Native to the GitHub open-source workflow, free for
  public repositories.
- **Problem solved:** Automated, visible quality gates for every contribution.
- **Cost:** YAML maintenance; runner time. Kept practical (Section 18).

### 4.12 OpenTelemetry + Micrometer — tracing and log correlation (direction, not a platform)

- **What:** Trace/metric emission and correlated logs; trace context propagated
  across the Java→Python→LLM path.
- **Java side:** Spring Boot 4 ships an official **`spring-boot-starter-opentelemetry`**
  that uses **Micrometer** (version managed by Spring Boot — the 1.x line;
  Micrometer 2.0 is only a milestone) internally and exports over **OTLP**. This
  is the recommended path for a new Spring Boot 4 app and replaces the need to
  choose between the standalone OpenTelemetry Java agent and Micrometer Tracing.
- **Python side:** the **OpenTelemetry Python SDK** (currently API/SDK ~1.3x,
  instrumentation ~0.6xb) with the FastAPI and httpx instrumentations and an OTLP
  exporter.
- **Fit / limit:** The MVP emits telemetry and, by default, exports to logs plus
  a metrics endpoint; an optional local OTel collector can be added to Compose.
  **No hosted monitoring stack, no dashboards** are built now
  (`docs/01-product-spec.md` Section 8; Section 14, Section 25).
- **Cost:** SDK dependencies and a small amount of wiring. Contained.

### 4.13 OpenAPI — API contract

- **What:** The REST API is described by an OpenAPI 3.1 document, generated from
  the backend (**springdoc-openapi 3.x**, which is the line compatible with
  Spring Boot 4 — its major version tracks the Spring Boot major) and from the
  Python service (FastAPI, built in).
- **Why:** A machine-readable contract for the React frontend and for Java↔Python
  contract tests.
- **Problem solved:** Drift between client and server; manual API docs.
- **Cost:** Keep annotations accurate. Low.

### 4.14 React + TypeScript + Vite — frontend

- **What:** Single-page web application; talks only to the backend REST API.
- **Versions:**
  - **React 19.2.x** (current stable).
  - **TypeScript 6.x** (current stable of the classic, JavaScript-based
    compiler). **TypeScript 7.0** (the native Go compiler) reached GA on
    2026-07-08 and is 8–12× faster, but at the verification date it lacks the
    stable programmatic API that `typescript-eslint` and some build plugins
    depend on (expected in 7.1). Signal Engine therefore starts on TypeScript 6.x
    for full tooling compatibility and treats **TypeScript 7 as a tracked
    migration** once `typescript-eslint` and the Vite React plugin certify it
    (Section 24, T22). TypeScript 5.9 is an acceptable fallback.
  - **Vite 8.x** (current stable) as the build tool.
  - **Vitest** (current major, kept aligned with the Vite major in use) for unit
    tests.
  - **ESLint 10.x** with flat config, plus `typescript-eslint` for TypeScript
    rules.
- **Why:** Agreed direction (React + TypeScript). Conventional,
  contributor-friendly.
- **Constraint:** No additional UI framework and **no state-management library**
  (Redux, MobX, Zustand, …) is introduced. React state, the router, and simple
  data-fetching hooks against the generated API client are sufficient for the MVP
  UI; a state library is added only if a concrete need appears.
- **Cost:** A JS toolchain in the repo and CI. Expected.

### 4.15 Python tooling: `uv`, `ruff`, `pytest`, `mypy`

- **What:** Project and dependency management + lockfile (**`uv`**, currently
  0.12.x), linting/formatting (**`ruff`**, currently 0.16.x), testing
  (**`pytest`**, currently 9.x), static typing (**`mypy`**, currently 2.x).
- **Decision:** **`uv` is the single Python project/dependency manager**, with a
  committed `uv.lock`. This resolves the earlier "`uv` or `pip-tools`" open
  choice — `uv` now covers environment creation, dependency resolution, locking,
  and running, is fast, and is widely adopted. **Poetry is not used** (no need
  for a second competing project manager).
- **Why:** Standard, fast, low-ceremony Python quality tooling; all target
  Python 3.13. (`ty`, a faster type checker, is emerging but not yet mature —
  `mypy` remains the choice; revisit later.)
- **Cost:** Minimal; all are widely used.

### 4.16 Java quality tooling: Spotless/Checkstyle, JaCoCo

- **What:** Formatting/style (**Spotless** with a standard config; optionally
  **Checkstyle**), coverage (**JaCoCo**).
- **Why:** Consistent style and visible coverage for contributors.
- **Cost:** Build-time checks; minimal.

### 4.17 Build tools

- **Java: Gradle 9.x (Kotlin DSL).** This settles the earlier Gradle-vs-Maven
  open choice. At the verification date Gradle 9.x is stable, supports Java 25
  (since 9.1), and is well supported by Spring Boot 4; Apache Maven 4 is still in
  release-candidate status (stable Maven remains 3.9.x). Gradle 9 + Kotlin DSL
  gives type-checked build scripts, good build performance, and the version
  catalog for centralized dependency versions. Maven remains a reasonable
  alternative for contributors who strongly prefer it, but the project standard
  is Gradle.
- **Frontend:** a single package manager with a committed lockfile — **npm**
  (default, ships with Node) or **pnpm**; **proposed default: pnpm** for speed
  and strict dependency resolution (Section 24, T23). Node.js **22 LTS or 24 LTS**
  (the active LTS at build time).
- **Python:** `uv` with `pyproject.toml` + `uv.lock` (Section 4.15).

---

## 5. System Architecture

### 5.1 Components

```
┌─────────────┐        REST/JSON (OpenAPI)        ┌────────────────────────┐
│   React     │ ───────────────────────────────▶ │      Java Backend       │
│  Frontend   │ ◀─────────────────────────────── │   (Spring Boot)         │
└─────────────┘                                   │                        │
                                                  │  - API (inbound)       │
                                                  │  - Application/use cases│
                                                  │  - Domain              │
                                                  │  - Scheduling          │
                                                  │  - Processing lifecycle│
                                                  │  - Persistence coord.  │
                                                  └───────┬───────┬────────┘
                                                          │       │
                          JDBC (transactions)             │       │  HTTP/JSON
                                                          ▼       │  (versioned contracts)
                                                  ┌───────────────┐│
                                                  │  PostgreSQL   ││
                                                  │  + pgvector   ││
                                                  └───────────────┘│
                                                                   ▼
                                                  ┌────────────────────────┐
                                                  │  Python AI Agent(s)    │
                                                  │  (FastAPI)             │
                                                  │  - classification      │
                                                  │  - relevance           │
                                                  │  - importance          │
                                                  │  - summarization       │
                                                  │  - embeddings          │
                                                  │  - answer synthesis    │
                                                  │  - similarity          │
                                                  └───────────┬────────────┘
                                                              │  LLM provider abstraction
                                                              ▼
                                                  ┌────────────────────────┐
                                                  │  Ollama (gpt-oss:20b)  │
                                                  │  + future providers    │
                                                  └────────────────────────┘
```

### 5.2 Responsibility summary

| Component | Owns | Does not own |
|-----------|------|--------------|
| **React frontend** | Presentation, user interaction | Any business data, any DB access |
| **Java backend** | Business state, transactions, orchestration, scheduling, processing lifecycle, API, provenance integrity | Semantic judgement, model inference |
| **PostgreSQL + pgvector** | Durable business state and embeddings | Business rules |
| **Python agents** | Stateless AI/NLP capabilities behind explicit contracts | Business persistence, transactions, orchestration, processing state |
| **LLM runtime** | Model inference | Anything Signal-Engine-specific |

### 5.3 Deployment shape (MVP)

A single logical deployment run locally via Docker Compose: one backend
container, one Python agent container (may be split later), one PostgreSQL
container, one frontend (served statically or via a dev server), and Ollama
(host or container). This is a **modular monolith backend + one AI sidecar**, not
a microservice system. Splitting Python capabilities into multiple services is
possible later without changing contracts (Section 21).

### 5.4 Communication style

- **Frontend ↔ backend:** synchronous REST/JSON over HTTP.
- **Backend ↔ Python:** synchronous HTTP/JSON request/response with versioned
  contracts (Section 8). No message broker (Section 25).
- **Backend ↔ PostgreSQL:** JDBC; the backend is the only writer of business
  tables.
- **Python ↔ LLM runtime:** through the LLM provider abstraction (Section 9).
- **Backend internal pipeline:** in-process stages driven by a scheduler and a
  persisted work queue table (Section 10), not an external queue.

---

## 6. Java Backend Architecture

### 6.1 Layering

Conceptual modules (exact package layout is `04-architecture.md`'s job):

- **domain** — entities, value objects, domain services, domain errors. Pure
  Java, no Spring, no JPA, no HTTP. Encodes deterministic business rules
  (identity, state machine transitions, provenance invariants).
- **application** — use cases / orchestration services; defines **ports**
  (interfaces) for everything external (repositories, AI capabilities, source
  collectors, clock, ID generation). Transaction boundaries are defined here.
- **interfaces / inbound adapters** — REST controllers, DTOs, request validation,
  scheduling entry points, mapping to/from application commands. Spring lives
  here.
- **infrastructure / outbound adapters** — Spring Data JDBC repository
  implementations, Flyway, the Python agent client, the LLM provider adapter
  (Spring AI, isolated), source-collector implementations, telemetry. Spring and
  third-party SDKs live here.

Dependency rule: `interfaces → application → domain` and
`infrastructure → application → domain`. Nothing points outward.

### 6.2 Composition root

A single Spring configuration area wires port implementations. Provider and
connector selection is resolved here from configuration (Section 15), so the
rest of the code is provider-agnostic.

### 6.3 Orchestration and processing lifecycle

- The backend runs the ingestion→processing→signal pipeline as a sequence of
  **stages**, each a use case, each with its own transaction and its own
  persisted outcome (Section 10).
- A **scheduler** (Spring scheduling; cadence is an open product question,
  `docs/02-functional-spec.md` Q9) triggers collection and drives pending work.
- Processing is **item-scoped**: one raw item flows through the stages; a failure
  in one item never blocks others (`docs/02-functional-spec.md` R10).

### 6.4 Persistence coordination

- Repositories are ports in the application layer, implemented with **Spring
  Data JDBC** in infrastructure. This settles the earlier JDBC-vs-JPA open
  choice: Signal Engine's data is straightforward relational data with explicit
  persistence boundaries and no requirement for a complex ORM domain model or
  lazy-loaded object graphs, so Spring Data JDBC (simple SQL, no persistence
  context, no lazy-loading surprises, a cleaner match for Clean Architecture) is
  the better fit. Hand-written SQL / `JdbcClient` is used for vector queries and
  any query Spring Data JDBC does not express well. JPA/Hibernate is not used.
- The backend owns all transactions. A Python call is **never** inside a database
  transaction — the backend commits state, calls Python, then commits the
  result, so a slow or failing agent never holds a DB transaction open.

### 6.5 Scheduling

- Recurring collection and retry sweeps are scheduled in the backend.
- The MVP runs a single backend instance; scheduling assumes no multi-node
  coordination. If multi-instance is ever needed, a DB-based lock (e.g.
  ShedLock) can be added — not now (Section 25).

### 6.6 API layer

- REST under `/api/v1`, JSON, described by OpenAPI (springdoc).
- Resource areas: `sources`, `interests`, `signals`, `search`, `questions`,
  `activity`, `alert-settings`. Exact resources follow
  `docs/02-functional-spec.md` workflows W1–W11.
- Input validation at the boundary (Jakarta Validation). Controllers map DTOs to
  application commands and back; no business logic in controllers.
- No authentication in the MVP (`docs/02-functional-spec.md` R14); the API binds
  to localhost by default (Section 20).

---

## 7. Python Agent Architecture

### 7.1 Nature and dependency composition

- Python exposes **stateless capabilities**: each is a pure function of its
  request plus the configured model(s). No business database access, no
  knowledge of processing state, no orchestration, no scheduling.
- One deployable service for the MVP, with capabilities as separate endpoints /
  modules. It **may** be split into multiple services later; the contracts do not
  change (Section 21).

**Dependency composition is native Python — it does not reproduce Spring's
dependency-injection model.** Java/Spring and Python are separate ecosystems and
each uses its own idiomatic mechanism; a Python abstraction is not introduced
merely because an equivalent exists on the Java side. Concretely:

- Dependencies (the configured LLM provider client, the prompt loader, the
  embedding client, and similar collaborators) are **composed explicitly at the
  application's composition entry point** — the module that builds the app —
  by constructing concrete objects and passing them into capability handlers as
  plain constructor/function arguments. There is no hidden global registry and
  no service locator.
- **Lightweight abstractions** — `typing.Protocol`, or `abc.ABC` where
  inheritance genuinely helps — **may** be used, but only when they provide a
  concrete benefit: **replaceability** (e.g. swapping the LLM client),
  **testability** (faking a dependency in a test), or a clear **separation of
  responsibilities**. An abstraction is not added just because Spring would
  express the same thing as an interface, a `@Service`, or a `@Component`.
- **A Python dependency-injection container is not required for the MVP** and is
  not introduced. No DI/IoC framework, and no other Python framework, is added
  merely to reproduce Spring concepts in Python.
- **FastAPI's own dependency mechanism (`Depends`) may be used for HTTP-bound
  concerns** — for example reading a header, validating a request, or providing
  a request-scoped client — but it **must not become the general application
  architecture**. Capability logic is plain Python, composed and testable
  independently of FastAPI.

Conceptually:

```
FastAPI                              (HTTP boundary; Depends for
      ↓                               request-scoped concerns only)
Application capability               (a plain function or small class)
      ↓
Native Python composition /          (explicit construction; typing.Protocol
lightweight Protocols                 or ABC only where it earns its keep)
      ↓
Concrete implementation              (LLM provider client, prompt loader, …)
```

This keeps the Python side lightweight and Python-native — no Java/Spring-style
service/component layer, no general-purpose DI container, no framework adopted
solely to mirror a Java concept — while preserving the existing responsibilities
(Section 7.2–7.5): capabilities remain stateless, own no business persistence, do
no orchestration or scheduling, hold no processing state between requests,
communicate only through the explicit versioned contracts (Section 8), stay
independent of any specific provider or model (Section 9), and never grow into a
single "do everything" agent (`CLAUDE.md` Section 12).

### 7.2 Capabilities (initial set)

| Capability | Input (summary) | Output (summary) | Nature |
|------------|-----------------|------------------|--------|
| `classify` | normalized item text + area-of-interest catalog + user interests | area(s) it relates to, information type, per-label rationale | LLM |
| `assess-relevance` | item text + areas + interests | relevant (bool), reason, matched interests, optional degree | LLM |
| `assess-importance` | item text + relevance context | important-enough (bool), reason, uncertainty | LLM |
| `summarize` | item text (+ source metadata) | concise summary, facts-vs-interpretation separation, grounding notes | LLM |
| `embed` | text | vector(s) + model id + dimension | embedding model |
| `answer` | question + retrieved context passages (provided by Java) | grounded answer + per-claim citations, or "insufficient information" | LLM |
| `similarity` | candidate text + comparison texts or vectors | near-duplicate score(s) | embedding model / deterministic math |

`classify`, `assess-relevance`, and `assess-importance` **may** be implemented as
one agent with multiple prompt steps or as separate agents — an implementation
choice deferred to `06-ai-agents.md`. The Java side sees separate contracts
regardless.

*Implemented set (`docs/adr/0006`, `0007`, `0008`, `0011`, `0015`): the running
capabilities are `echo` (a transport proof), `near-duplicate`, `relevance`
(which also does area/interest matching — there is no separate `classify`),
`importance`, `summarize`, `embed`, `semantic-chunk-boundary`, and `answer`.
Exact field names and schemas are fixed by those ADRs and the shared contract
fixtures in `agents/contract/`, not by this table.*

### 7.3 Multiple LLM calls are allowed when they add value

Because the initial LLM is local, the architecture does **not** prematurely
minimise call count. Additional calls are acceptable when they measurably improve
extraction, understanding, relevance, importance, classification, or
summarization quality (for example a separate extraction pass before
summarization, or a repair pass on malformed output). Calls that do not have a
clear purpose are not added.

### 7.4 What Python must not do

- No writes to business tables; no direct database connection to the business
  schema. (A Python capability that needs a vector similarity search receives the
  candidate vectors/passages from Java, or Java performs the pgvector query — the
  division is settled in `07-rag.md`. Default: **Java performs retrieval**,
  Python synthesizes.)
- No orchestration, scheduling, or retry-policy ownership.
- No holding of processing state between requests.

### 7.5 Prompt and model management

- Prompts are **versioned assets** co-located with the Python agent (templated,
  reviewed, not scattered inline). The prompt version used is returned in every
  response for traceability.
- Model selection per capability is **configuration** (Section 9.3), allowing
  different models for different capabilities later.

---

## 8. Java / Python Contracts

### 8.1 Principles

- **Explicit request/response contracts**, one per capability, each **versioned**
  (`v1`, …). Example pairs: `RelevanceRequest → RelevanceResponse`,
  `SummaryRequest → SummaryResponse`.
- Contracts are **schema-first**: a JSON Schema (or the OpenAPI component
  generated by FastAPI/Pydantic) is the source of truth, shared with the Java
  side for validation and contract tests.
- Transport: **HTTP/JSON**, synchronous. Chosen because the interaction is
  request/response, the two components are co-located, and no
  fan-out/streaming/backpressure requirement exists. A broker would add
  operational cost with no matching requirement (Section 25).

### 8.2 Common envelope

Every request carries, at minimum:

- a **processing/correlation id** (Section 14),
- the **contract version**,
- the **payload** for that capability.

Every response carries, at minimum:

- the **correlation id** echoed,
- the **contract version**,
- **model/provider metadata** (provider id, model id, prompt version),
- **timing** (duration, token counts where available),
- the **typed result**, or a **typed error** (Section 13.5),
- for semantic outputs: a **facts-vs-interpretation** distinction and, where
  meaningful, an **uncertainty/confidence** indicator.

### 8.3 Validation

- **Python side:** validates the inbound request against the contract; validates
  the LLM's structured output against the capability's result schema before
  returning; on failure applies a bounded repair step, then returns a typed
  error (Section 9.6).
- **Java side:** validates every Python response against the contract schema
  **before** the result enters business logic. A schema-invalid response is a
  non-retryable `AI_CONTRACT_VIOLATION` for that item (Section 13).

### 8.4 Versioning and compatibility

- Additive changes bump a minor version and remain backward compatible.
- Breaking changes introduce a new versioned endpoint; the backend pins the
  version it calls. Old and new can run side by side during a migration.
- Contract tests (Section 16.6) run in CI on both sides against the shared
  schema.

### 8.5 Failure and timeout behavior

- The Java client sets an explicit per-call **timeout** (proposed defaults in
  Section 13.4) that is **longer** than the Python service's own internal model
  timeout, so the layer that best understands the failure reports it first.
- Java treats transport/timeout/5xx as **retryable** (bounded); schema
  violations and explicit non-retryable typed errors as **non-retryable**
  (Section 13).

---

## 9. AI and LLM Architecture

### 9.1 Layers

```
Application use case (Java)  or  Python capability handler
            │  depends on a port / interface only
            ▼
AI capability service        (e.g. RelevanceAssessor, Summarizer, Embedder)
            │
            ▼
LLM provider abstraction     (LlmChatProvider, EmbeddingProvider)
            │
            ▼
Provider adapter             (OllamaChatAdapter, future: other adapters)
            │
            ▼
Model runtime                (Ollama / future providers)
```

No layer above the provider adapter names a provider or model. `ollama` and
`gpt-oss:20b` appear only in configuration and in the adapter.

### 9.2 Provider abstraction

- A minimal port surface: a chat/generate operation with structured-output
  support, and an embedding operation. Each takes a request describing messages/
  input, generation parameters, an output schema, and a timeout; returns text/
  structured output plus metadata.
- Adapters implement the port per provider. Adding a provider = new adapter +
  configuration; **no change to capability services or business logic**
  (Section 21).
- On the Java side, the adapter is implemented using **Spring AI 2.0.x**
  (Section 4.7), wholly contained in infrastructure and never exposed to the
  application or domain layers.
- On the Python side — where most LLM calls originate (Section 7.3) — the same
  port is a plain Python object behind a lightweight `typing.Protocol`, composed
  natively (Section 7.1). It does **not** use Spring AI or any DI container; the
  "port" here means an explicit, swappable interface, not a Spring-style bean.

### 9.3 Model configuration

- Per **capability**, configuration specifies: provider id, model id, generation
  parameters (temperature, max tokens, etc.), timeout, and prompt version.
- Defaults: all capabilities' generative LLM → **NVIDIA Build**
  (`AGENTS_LLM_PROVIDER=nvidia`) — the final provider decision (Section 17.2;
  `docs/adr/0006-ai-java-python-foundation.md`), not the originally proposed
  Ollama + `gpt-oss:20b`, which remains a selectable fallback; embeddings →
  Ollama running `embeddinggemma` (**provisional default**, Section 24, T3;
  `docs/adr/0011-embedding-contract-and-local-model.md`).
- The design permits **different models per capability** later (for example a
  smaller model for classification, a larger one for summarization) purely
  through configuration.

### 9.4 Structured outputs

- Capabilities that feed business logic (`classify`, `assess-relevance`,
  `assess-importance`, `answer` citations, `similarity`) request **structured
  output** with an explicit schema.
- `summarize` returns structured fields (summary text, grounding notes,
  interpretation flags) rather than free text only.

### 9.5 Prompt management

- Prompts are versioned, templated files under the Python agent, reviewed like
  code.
- A prompt change is a version bump recorded in responses and in activity, so a
  behavior change is traceable.
- Prompts encode the source-grounding and no-advice rules
  (`docs/02-functional-spec.md` R2, R4, R25) but these rules are **also enforced
  structurally** (validation, retrieval design) — not by prompt wording alone
  (`CLAUDE.md` Section 16).

### 9.6 Malformed / low-quality output handling

1. Prefer the provider's **JSON-Schema-constrained** output mode (supported by
   Ollama and by Spring AI's structured-output binding). Spring AI 2.0's
   self-correcting structured output is used on the Java side where applicable.
2. Validate structured output against the capability's schema.
3. On failure: one bounded **repair attempt** (re-prompt with the validation
   error), **proposed default: 1 retry**. A model that returns a
   reasoning/"thinking" trace instead of schema-valid JSON is treated as a
   malformed output; the configured default model avoids this, and the
   per-capability configuration can fall back to prompt-based extraction.
4. On repeated failure: return a typed `AI_OUTPUT_INVALID` error. The item moves
   to a `FAILED` processing state with a clear reason; it is visible in activity
   and can be reprocessed later (`docs/02-functional-spec.md` W4 error cases).
5. No fabricated result is ever substituted (`docs/02-functional-spec.md` R11).

### 9.7 Timeouts

- Each LLM call has an explicit timeout from capability configuration.
- Local inference is slow; timeouts are generous by default and tunable
  (proposed defaults, Section 13.4). Timeout → retryable failure with backoff,
  bounded.

### 9.8 Source grounding

- For `answer` (RAG), retrieval is performed by Java over pgvector with metadata
  filtering; the retrieved passages (with their source references) are passed to
  Python; the answer must cite only those passages; "insufficient information" is
  a valid, expected response (`docs/02-functional-spec.md` R2, Section 13).
- For `summarize`, the summary is constrained to the provided item content and
  returns grounding notes; uncited claims are treated as a quality failure by
  evaluation later (not built now).

### 9.9 Cost/latency stance

Quality and maintainability take precedence over call-count minimisation for the
local-LLM MVP. Batching, caching of identical prompts, and call consolidation are
**allowed optimizations** but are added only with evidence, not preemptively.

---

## 10. Ingestion Architecture

### 10.1 Pipeline stages and boundaries

```
Source (config)
   │
   ▼  Collector           — fetch raw payloads from a source (per-source-type adapter)
   ▼  Parsing             — turn a payload into candidate item records (per-source-type)
   ▼  Normalization       — canonical internal form: text, source ref/link, timestamps, language
   ▼  Content extraction  — extract the meaningful content body (deterministic; AI-assisted only if needed)
   ▼  Deduplication       — deterministic identity/hash checks, then semantic near-duplicate check
   ▼  AI processing       — classify → assess relevance (+ area tagging) → assess importance
   ▼  Signal decision     — deterministic state transition in Java from the importance assessment
   ▼  Summarization       — for created signals
   ▼  Persistence         — relevant information, signals, summaries, embeddings, activity
```

Each arrow is a boundary with a defined input and output. Fetching, parsing,
normalization, and content extraction are **deterministic** and live in Java
(with a per-source-type adapter for fetch+parse). Semantic near-duplicate
scoring, classification, relevance, importance, and summarization are **semantic**
and delegated to Python capabilities. The **signal state transition** and all
persistence are Java.

### 10.2 Source connectors

- A **`SourceCollector` port** in the application layer; one adapter per **source
  type** in infrastructure.
- Adding or replacing a source is **configuration**
  (`docs/02-functional-spec.md` R22). Adding a **new source type** is a new
  adapter implementing the port — no change to normalization, dedup, AI
  processing, signals, search, or alerting.
- The concrete source types and connectors are an **open question**
  (`docs/02-functional-spec.md` Q1) and are specified in `08-ingestion.md`. This
  document fixes only the boundary and the port.

### 10.3 Processing states

Persisted per raw item (illustrative; final set in `05-data-model.md`):

```
RECEIVED → NORMALIZED → (DUPLICATE | DEDUPLICATED)
        → CLASSIFIED → RELEVANCE_ASSESSED → (NOT_RELEVANT | RELEVANT)
        → IMPORTANCE_ASSESSED → (SIGNAL_CREATED | NO_SIGNAL)
        → SUMMARIZED → COMPLETE

any stage → FAILED{stage, reason, retryable}
FAILED{retryable} → PENDING_RETRY → (re-enters at the failed stage)
```

State is always persisted before and after a stage, so an item's position and
history are always known (`docs/02-functional-spec.md` Section 15, R9).

### 10.4 Idempotency

- Each raw item has a **deterministic identity**: `(source id, external item id)`
  where the source provides one, plus a **content hash** of the normalized
  content.
- Collection **upserts** by identity: re-collecting the same item does not create
  a duplicate row (`docs/02-functional-spec.md` W3 "same item seen again").
- Each stage is safe to re-run: outputs are written by upsert keyed to the item +
  stage, so a retry after a partial failure converges to one correct result.
- AI calls are not themselves idempotent, but the **persisted stage outcome** is
  single-valued per item; a re-run replaces the prior attempt's outcome and
  records the new attempt in activity.

### 10.5 Deduplication split

- **Deterministic first:** exact identity match and content-hash match →
  `DUPLICATE`, attached to the existing item/group with its source reference
  (`docs/02-functional-spec.md` R5, W4).
- **Semantic second:** for non-identical items, an embedding similarity score
  (Python `similarity` / `embed`) compared against a **configurable threshold**
  (deterministic decision in Java). Threshold and the near-duplicate criteria are
  an **open question** (`docs/02-functional-spec.md` Q12) — the mechanism is
  fixed here, the cutoff is not.

### 10.6 Not defined here

Final schema, connector implementations, chunking/embedding granularity, and
retrieval strategy are for `05-`, `07-`, and `08-`.

---

## 11. Persistence and Vector Search

### 11.1 PostgreSQL as the single store

- One database — **PostgreSQL 17** (Section 4.2) — owned by the backend. Business
  tables, processing state, activity records, and embeddings all live here,
  giving transactional consistency between a signal, its summary, its sources,
  and its vector.
- Schema evolves only through **Flyway** migrations, reviewed in pull requests.

### 11.2 pgvector

- **pgvector 0.8.x** (Section 4.3). Embeddings are stored in `vector` columns.
  *Task 8.3B (`docs/adr/0012`): they live on a dedicated `rag_passage_embedding`
  table keyed to a derived `rag_passage` (a technical chunk), one row per
  (passage, embedding model); `vector(768)` for the current provisional model.
  See `07-rag.md` Section 23.*
- ANN index type (HNSW vs IVFFlat) and parameters are a **proposed default /
  tuning decision** (Section 24, T7); HNSW is the likely default for recall at
  small scale. *Task 8.3B created no ANN index — deferred to T7 with real corpus
  size; cosine is the chosen distance.* *Task 8.3C (`docs/adr/0013`) confirms the
  deliberate exact-scan baseline: `PgVectorRetriever` does an exact cosine (`<=>`)
  Top-K scan; the exact→ANN comparison waits for T7 with a real corpus.*
- The embedding model and vector dimension are configuration; changing the model
  requires a re-embedding migration path (documented in `07-rag.md`).

### 11.3 Search

- Semantic search = an embedding of the query + a pgvector nearest-neighbor query
  + deterministic **metadata filters** (area of interest, source, time period,
  state) applied in SQL (`docs/02-functional-spec.md` Section 12).
- No reranking in the MVP (`docs/01-product-spec.md` Section 8;
  `docs/02-functional-spec.md` R19).
- *Task 8.3C (`docs/adr/0013`, `07-rag.md` Section 24): the generic
  `Retriever` now has a first real implementation — `Query → EmbeddingModel
  (TextRole.QUERY) → exact pgvector cosine (`<=>`) Top-K`. `RetrievedPassage.score
  = 1 − cosineDistance`. Configurable `topK` (provisional default 5, hard ceiling
  200). Only the generic provenance-identity filters `contentId`/`sourceId` are
  honoured so far; area / interest / time-window filtering is a business concern
  and stays deferred. Hybrid retrieval and reranking remain deferred. Benchmark
  over the 8.3A corpus: Recall@1 0.958, Recall@3–10 / MRR / nDCG@10 = 1.000, ~123
  ms/query — matching the 8.3A embedding-only baseline.*
- *Task 8.4 (`docs/adr/0014`, `07-rag.md` Section 25): the generic
  `ContextAssembler` now has a first real implementation —
  `BudgetedContextAssembler` selects whole passages in retrieval order until a
  **character** budget (default 12 000, hard ceiling 200 000; model-independent,
  configured by `signal-engine.rag.context.max-context-characters`) is reached.
  It removes exact-duplicate passage ids, never reorders, and never modifies
  passage text — no summarisation or compression (deferred to a future
  `ContextRefiner`). Provenance and retrieval score survive into every
  `ContextPassage`. `Context` stays a structured object, not a prompt string. A
  minimal query-time `RagPipeline` bean (retriever + assembler, no generator) is
  composed; nothing consumes it yet.*
- *Task 8.5 (`docs/adr/0015`, `07-rag.md` Section 26): the generic `Generator`
  now has a first real implementation. `AiCapabilityAnswerGenerator`
  (infrastructure) calls a new `answer` Python capability (contract v1) through
  the Task 6A `AiCapabilityInvoker` — provider-independent, no LLM named in the
  RAG core. Structured JSON output (`answered`, `answer`, `citations` of passage
  ids only); Java attaches each citation's provenance from the context, never from
  the model. Grounding is validated structurally in three layers (Python
  `extra_check` + one bounded repair, Java adapter re-check, RAG-core
  `GroundingAnswerValidator` which drops unresolvable citations and downgrades an
  unsupported "answered" verdict to insufficient evidence). Retrieved passage text
  is treated as untrusted (prompt-injection framing). A provider/timeout/repair
  failure is a typed `GenerationException`, never a fabricated answer.
  **Structural grounding only — semantic factuality/faithfulness scoring is
  deferred to the separate `Evaluator` concern.** The query-time `RagPipeline`
  bean now includes the generator + validator; still nothing consumes it (no
  query API).*

### 11.4 Data retention

- Raw items, relevant information, signals, summaries, feedback, and embeddings
  are retained for the life of the MVP install.
- Activity-history retention is an **open question**
  (`docs/02-functional-spec.md` Q22); the schema will allow a retention/purge job
  to be added without redesign.

### 11.5 Backups

- Local development: none required.
- A documented `pg_dump`-based backup/restore procedure is provided for users who
  run Signal Engine persistently. Automated backup infrastructure is out of scope
  for the MVP.

---

## 12. API and Frontend Integration

### 12.1 API

- REST/JSON under `/api/v1`, described by OpenAPI 3 (springdoc), served at a
  documented path in non-production profiles.
- Resource areas map to functional workflows: sources (W1), interests (W2),
  signals + review + feedback (W6, W7), search (W9), questions (W10), activity
  (W11), alert settings (W8/Section 11), collection trigger if adopted (W3 / Q8).
- Errors use a consistent problem format (**proposed default: RFC 7807
  `application/problem+json`**) with a stable error code, a human-readable
  message, and the correlation id (Section 13.7).
- Pagination, filtering, and sorting on list endpoints; defaults where the
  product leaves them open are marked as **proposed** (e.g. signal ordering,
  `docs/02-functional-spec.md` Q16).

### 12.2 Frontend integration

- The React app consumes only `/api/v1`. **No direct database access**
  (`CLAUDE.md` Section 14; `docs/02-functional-spec.md` out-of-scope).
- A typed API client is **generated from the OpenAPI document** so the frontend
  stays in sync with the contract.
- The frontend is built in CI and served either as static assets by the backend
  or from its own container in Compose (**proposed default: static assets served
  by the backend** to keep the local setup to one origin; revisit — Section 24,
  T8).

### 12.3 Real-time updates

- The MVP uses **polling** for new signals/activity in the UI. WebSockets/SSE are
  a possible later enhancement, not built now (Section 25). Alert *delivery*
  channel is an open product question (`docs/02-functional-spec.md` Q19) and is
  not resolved here.

---

## 13. Error Handling and Resilience

### 13.1 Error taxonomy

| Category | Meaning | Examples | Default disposition |
|----------|---------|----------|---------------------|
| **Domain error** | A business rule was violated | invalid state transition, provenance invariant broken | Non-retryable; surfaced as a 4xx or a processing failure with a clear code |
| **Application error** | A use case cannot proceed | referenced source not found, interest empty | Non-retryable; 4xx |
| **Validation error** | Input or contract payload is malformed | bad request body, `AI_CONTRACT_VIOLATION` | Non-retryable |
| **Infrastructure error** | A dependency failed transiently | DB connection blip, Python service 503 | Retryable with backoff |
| **External source failure** | A source could not be fetched/parsed | source unreachable, parse error | Retryable per source on next collection; recorded per source |
| **AI/provider failure** | The model runtime failed or timed out | Ollama down, timeout, `AI_OUTPUT_INVALID` after repair | Timeout/transport → retryable; invalid output after repair → non-retryable for that attempt |
| **Timeout** | An operation exceeded its budget | LLM call, Python call, source fetch | Retryable with backoff, bounded |

### 13.2 Retryable vs non-retryable

- **Retryable:** transient infrastructure faults, timeouts, source unreachable,
  provider transport/5xx.
- **Non-retryable:** validation failures, contract violations, domain rule
  violations, invalid AI output after the bounded repair, "no usable content".
- Non-retryable failures move the item to `FAILED` with a stable reason code and
  stop consuming retry budget.

### 13.3 Retry and backoff

- **Bounded** retries with **exponential backoff and jitter**.
- **Proposed defaults** (all configurable, all open to tuning — Section 24, T9):
  - Python capability call: up to **3** attempts, base delay **2s**, factor
    **2.0**, max delay **30s**.
  - Source collection: retried on the **next scheduled collection**, not in a
    tight loop; a per-source failure counter is recorded. Backoff/limit per
    source is an open product question (`docs/02-functional-spec.md` Q10).
  - LLM structured-output repair: **1** in-call repair attempt (Section 9.6).
- Retries are only applied to retryable categories.

### 13.4 Timeouts (proposed defaults)

All configurable; values chosen conservatively for **local** inference and
subject to tuning (Section 24, T9):

- Source fetch: **30s** connect+read (per source type may override).
- Python capability HTTP call (Java client): **120s**.
- LLM chat call (inside Python): **90s**.
- Embedding call: **30s**.
- DB statement timeout: **15s** for interactive queries; batch/processing
  queries may set their own.

Rule: an outer timeout is always greater than the inner timeout it wraps
(Section 8.5).

### 13.5 Typed errors across the Java/Python boundary

- Python returns a structured error object: `{ code, category, retryable,
  message, correlationId, details? }`.
- Java maps it to its taxonomy and to a processing outcome. Unknown codes default
  to retryable infrastructure error, capped by the retry budget.

### 13.6 Idempotency and partial failure

- Because stages are idempotent (Section 10.4), a retry after a partial failure
  is safe.
- A failure in one item, one source, or one AI step never halts the pipeline for
  other items (`docs/02-functional-spec.md` R10).

### 13.7 User-facing errors

- Mapped to a stable error code + clear English message + correlation id
  (`docs/02-functional-spec.md` Section 15.2).
- Processing failures are not thrown at the user ad hoc; they appear in the
  **activity view** with enough context to identify the source/item
  (`docs/02-functional-spec.md` W11).
- Never fabricate a result to mask a failure (`docs/02-functional-spec.md` R11).

### 13.8 No catch-all handling

- No broad `catch (Exception)` that swallows and continues. Exceptions are either
  handled at a boundary that understands them, or translated to a typed
  domain/application error, or allowed to fail the current item's stage with a
  recorded reason.

---

## 14. Logging and Observability

### 14.1 Structured logging

- Logs are **structured** (JSON in non-local profiles; human-readable in local
  dev).
- Every log line for pipeline work carries, where applicable:
  `timestamp`, `level`, `component`, `operation`, `processingId` /
  `correlationId`, `traceId`, `sourceId` / `itemId`, `capability` / `agent`,
  `provider` / `model` / `promptVersion`, `durationMs`, `status`,
  `errorCode` / `errorCategory`.
- Log **levels** are used deliberately: `ERROR` for actionable failures, `WARN`
  for handled degradations (retry scheduled, source skipped), `INFO` for stage
  transitions and outcomes, `DEBUG` for detail.

### 14.2 Correlation and tracing

- A **correlation/processing id** is created when a raw item enters the pipeline
  (and for each API request) and propagates through every stage, every Python
  call (in the request envelope, Section 8.2), and into log lines.
- **OpenTelemetry** provides distributed tracing: a span for the pipeline stage
  (Java) → a child span for the Python capability call → a child span for the LLM
  provider call. Trace context is propagated over HTTP (W3C `traceparent`). On
  the Java side this uses the Spring Boot 4 `spring-boot-starter-opentelemetry`
  (Micrometer + OTLP); on the Python side the OpenTelemetry Python SDK with the
  FastAPI and httpx instrumentations (Section 4.12).
- This answers "what happened, where did it fail, how long did it take, what did
  the AI do" (`CLAUDE.md` Section 25) without a dashboard.

### 14.3 Metrics (minimal)

- A small set of counters/timers: items collected, items processed per outcome,
  stage durations, Python call durations and failure rates, LLM call durations
  and failure rates, retries, source failure counts.
- Exposed via **Micrometer** (Spring Boot Actuator) and the OpenTelemetry OTLP
  export for local inspection. **No hosted metrics stack is provisioned**
  (Section 25).

### 14.4 What not to log

- No secrets, credentials, or connection strings.
- No unnecessary full source content at `INFO`; content excerpts only at `DEBUG`
  and only when needed for diagnosis.
- Prompts may be logged at `DEBUG` with a prompt version reference; large prompt
  bodies are not logged at `INFO`.

### 14.5 Scope limit

The MVP provides **telemetry emission + logs + a metrics endpoint**. Collector,
storage, dashboards, and alerting on telemetry are an **operational evolution**,
not MVP work (`docs/01-product-spec.md` Section 8).

---

## 15. Configuration and Secrets

### 15.1 Principles

- **12-factor style:** configuration comes from environment variables / mounted
  config, not from code.
- Sensible **non-secret defaults** for local development are committed (e.g. DB
  name, ports, Ollama URL); secrets are never committed.
- Spring profiles (`local`, `test`, `ci`, and a future `prod`) select
  environment-appropriate configuration.

### 15.2 Configuration domains

- **Database:** URL, credentials (secret), pool settings.
- **Python agent:** base URL, per-capability timeouts, retry policy.
- **LLM providers:** per-capability provider id, model id, generation params,
  timeouts, prompt versions; provider endpoint URLs; provider credentials
  (secret, for future cloud providers).
- **Scheduling:** collection cadence (proposed default + open — Q9), retry sweep
  interval.
- **Processing:** near-duplicate threshold (open — Q12), repair attempts, batch
  sizes.
- **API/frontend:** bind address, CORS for local dev, base path.

### 15.3 Secret handling

- Local: `.env` file that is **git-ignored**, plus a committed `.env.example`
  documenting every key with placeholder values.
- CI: GitHub Actions encrypted secrets; only what CI needs (no LLM secret needed
  while local-only).
- Future production: external secret source (env injection or a secret manager) —
  not designed now.
- The code reads secrets only through the configuration layer; no secret literal
  in source, tests, or fixtures (`CLAUDE.md` Sections 13, 24).

---

## 16. Testing Strategy

### 16.1 Layers

| Test type | Target | Tooling | Runs |
|-----------|--------|---------|------|
| **Domain unit tests** | Deterministic business rules: state machine, identity, provenance invariants, dedup identity/hash logic | JUnit 5, AssertJ | Every PR, fast |
| **Application/use-case tests** | Orchestration logic with ports mocked/faked | JUnit 5, Mockito or hand-written fakes | Every PR, fast |
| **Infrastructure integration tests** | Repositories, Flyway migrations, pgvector queries | Testcontainers (real PostgreSQL + pgvector) | Every PR, separate job |
| **API tests** | Controllers, validation, error mapping, OpenAPI conformance | Spring MockMvc / WebTestClient | Every PR |
| **Python unit tests** | Each capability's request handling, output validation, repair logic, error mapping — with the LLM client **faked** | pytest | Every PR |
| **Python integration tests (optional, gated)** | A capability against a real local model | pytest + a marker, not required to pass CI by default | On demand / nightly (proposed) |
| **Contract tests** | Java client ↔ Python service against the shared schema | schema validation both sides; provider/consumer style | Every PR |
| **Frontend tests** | Component and API-client behavior against a mocked API | Vitest / Testing Library | Every PR |

### 16.2 Deterministic AI test fixtures

- LLM and embedding calls are **faked** in automated tests using **recorded,
  checked-in fixtures**: representative request → canned structured response.
- Fixtures cover: well-formed output, malformed-then-repaired output,
  malformed-unrecoverable output, timeout, provider error, "insufficient
  information" answers.
- This makes AI-adjacent code deterministically testable **without** building the
  evaluation subsystem.

### 16.3 What is explicitly not built now

- No automated quality scoring of model outputs, no golden-answer regression
  suite for model quality, no evaluation dashboard. `docs/01-product-spec.md`
  keeps the evaluation subsystem for later; this spec only ensures the code is
  **structured to add it** (capability responses already carry model/prompt
  metadata and facts-vs-interpretation separation).

### 16.4 Coverage

- Coverage is measured (JaCoCo, `pytest-cov`) and reported in CI.
- A coverage **threshold** is a **proposed default** (e.g. fail under 70% on
  domain+application) — set once the codebase exists (Section 24, T10). Coverage
  is a guardrail, not a goal; tests target behavior, not lines
  (`CLAUDE.md` Section 20).

### 16.5 Test data and migrations

- Integration tests run the real Flyway migrations against the Testcontainers
  database, so migration breakage fails CI.

### 16.6 Contract test mechanics

- The Python service publishes its OpenAPI/JSON Schema as a build artifact.
- The Java build validates its client models and its recorded fixtures against
  that schema.
- A schema change that breaks the consumer fails CI before merge.

---

## 17. Docker and Local Development

### 17.1 Goal

`git clone` → a documented short sequence → a working system, per
`CLAUDE.md` Sections 21, 29.

### 17.2 Compose services

- `db` — PostgreSQL 17 + pgvector 0.8.x (image with the extension, or an init
  step).
- `backend` — the Spring Boot service.
- `agents` — the Python FastAPI service.
- `frontend` — dev server, or static assets served by `backend` (Section 12.2).
- `ollama` — started by the normal `docker compose up` (no profile gate), for
  **embeddings only** (`embeddinggemma`). The generative LLM is NVIDIA Build
  (`AGENTS_LLM_PROVIDER=nvidia`; Section 9.3) — final provider decision, not Ollama
  — so this service is no longer the MVP's blocker for relevance/importance
  assessment; it stays required only because RAG indexing needs a local embedding
  model. A companion one-shot `ollama-pull` service pulls the configured embedding
  model via the `ollama` container's own HTTP API — explicit and observable
  (`docker compose logs ollama-pull`), not assumed to already exist — without
  blocking `backend`/`agents` readiness on the download. A **host Ollama** with a
  configured `OLLAMA_URL` remains a supported alternative for embeddings (stop the
  `ollama`/`ollama-pull` services). Still never needed for tests — Testcontainers-
  based tests do not use Compose, and no test reaches a real LLM or embedding
  provider. The published port is overridable (`OLLAMA_PORT`) for a host that
  already runs its own Ollama on the default 11434.

### 17.3 Developer ergonomics

- A single entry point (a `Makefile` or a `justfile` / scripts) for:
  `up`, `down`, `migrate`, `test`, `lint`, `seed` (optional sample sources/
  interests for a first run).
- `.env.example` documents every variable.
- Hot-reload for backend (Spring DevTools) and frontend (Vite) in `local`.
- Testcontainers reuse enabled locally for faster iterative integration tests.

### 17.4 Reproducibility

- Pinned base images, pinned tool versions, locked dependencies (Gradle version
  catalog + Spring Boot BOM, `pnpm-lock.yaml`/`package-lock.json`, `uv.lock`).
- The same migrations, images, and lockfiles are used in CI.

---

## 18. CI/CD

### 18.1 Pull-request workflow (required to merge)

1. **Setup / cache** dependencies.
2. **Build** backend, agents, frontend.
3. **Static/quality checks:** Spotless/Checkstyle (Java), `ruff` + `mypy`
   (Python), ESLint + `tsc --noEmit` (frontend).
4. **Unit tests:** Java domain/application, Python, frontend.
5. **Integration tests:** Testcontainers (PostgreSQL 17 + pgvector), API tests,
   migration run — in a dedicated job with Docker available.
6. **Contract tests:** Java client vs Python schema (Section 16.6).
7. **Dependency/security checks:** one dependency-and-image vulnerability scanner
   — **Trivy** (covers Java, Python, and container images in a single tool, so no
   separate OWASP Dependency-Check is needed) — plus **Dependabot** for automated
   update PRs and **CodeQL** for SAST. These three are complementary, not
   duplicative: Trivy finds known-vulnerable versions, Dependabot proposes the
   upgrades, CodeQL finds code-level issues.
8. **Coverage** report (threshold gating is a proposed default, T10).
9. **Package** build artifacts (jar, Python wheel/image context, frontend
   bundle).

### 18.2 Main-branch workflow

- Everything in 18.1, plus:
- **Build container images** for `backend` and `agents` (and `frontend` if
  containerized).
- Optionally push images to **GitHub Container Registry** tagged with the commit
  SHA. Whether `main` pushes images or only tags do is a **proposed default**
  (Section 24, T11).

### 18.3 Release workflow (evolution)

- On a version tag: build images, generate a **SBOM**, publish images to GHCR
  with the version tag, attach build artifacts and a changelog to the GitHub
  Release.
- Semantic versioning. Release automation can be added once the first milestone
  is reached; it is **not required for the initial MVP** and is described here as
  an evolution.

### 18.4 Principles

- Fast feedback: quick jobs (lint, unit) gate before slow jobs (integration)
  where practical, but all required jobs must pass to merge.
- CI uses the same migrations, lockfiles, and container definitions as local dev.
- No deploy step in the MVP — there is no hosted environment
  (`docs/01-product-spec.md` personal-use MVP).

---

## 19. Dependency and Library Strategy

### 19.1 Rules (`CLAUDE.md` Section 23)

1. A dependency must solve a real, identified problem.
2. Check whether the existing stack already solves it.
3. Prefer a **mature, well-maintained** library over custom code for
   well-understood problems (HTTP, JSON, validation, migrations, testing,
   telemetry).
4. Write custom code for anything that is **core domain logic** or where a
   library would impose hidden coupling.
5. Record significant choices (this section + Section 23; ADRs in
   `docs/` per `CLAUDE.md` Section 22 as decisions mature).
6. Avoid sprawl: periodically review the dependency list; remove unused ones.

### 19.2 Accepted dependencies and their justification

Summarized here with the major/minor line verified 2026-09-04; the full
"what/why/problem/fit/cost" is in Section 4. Exact patch versions live in the
build files and lockfiles.

| Dependency | Line | Justification (short) |
|------------|------|------------------------|
| Java (JDK) | 25 LTS (21 LTS fallback) | Runtime platform |
| Spring Boot | 4.1.x | Backend framework: API, DI, scheduling, transactions, testing |
| Spring Framework | 7.0.x (via Spring Boot) | Core framework |
| Spring Data JDBC | via Spring Boot BOM | Explicit repository/persistence with plain SQL |
| Flyway (Community) | current stable | Versioned, reviewable SQL migrations |
| PostgreSQL JDBC driver | via Spring Boot BOM | Database access |
| PostgreSQL (server) | 17.x | Source of truth |
| pgvector (extension) | 0.8.x | Vector search without new infrastructure |
| Jakarta Validation | via Spring Boot BOM | Declarative input validation at boundaries |
| springdoc-openapi | 3.x | Generated OpenAPI contract (Spring Boot 4 line) |
| `spring-boot-starter-opentelemetry` + Micrometer | via Spring Boot BOM | Metrics + tracing + log correlation |
| Testcontainers | 2.x (via Spring Boot BOM) | Real-PostgreSQL integration tests |
| JUnit 5 / AssertJ / Mockito | via Spring Boot BOM | Java testing |
| Spotless (+ Checkstyle optional) / JaCoCo | current stable | Java style + coverage |
| Spring AI | 2.0.x | Java-side LLM client + structured output, isolated behind our ports |
| Gradle | 9.x | Java build tool |
| Python | 3.13.x | Runtime for the AI agents |
| FastAPI / Pydantic v2 / Uvicorn | 0.14x / 2.13.x / 0.52.x | Python service: typed contracts + validation + OpenAPI |
| httpx | 0.28.x | Outbound HTTP (to the Python agents from tests, and to the LLM runtime) |
| `ollama` Python client (or direct HTTP) | current stable | Talking to the local model runtime, behind the provider abstraction |
| OpenTelemetry Python SDK + FastAPI/httpx instrumentation | current stable | Tracing/metrics on the Python side |
| pytest / ruff / mypy | 9.x / 0.16.x / 2.x | Python testing and quality |
| uv | 0.12.x | Python project + dependency manager + lockfile |
| React | 19.2.x | Frontend framework |
| TypeScript | 6.x (7.x tracked) | Frontend type system |
| Vite / Vitest | 8.x / current major | Frontend build + unit tests |
| ESLint (+ `typescript-eslint`) / Prettier | 10.x / current | Frontend quality |
| Node.js | 22 LTS or 24 LTS | Frontend build runtime |
| pnpm (or npm) | current stable | Frontend package manager + lockfile |
| Trivy / CodeQL | current | CI dependency + image scanning / SAST |

No dependency in this list is new relative to the previous version of this
document except the ones that replace an earlier "either/or" (uv, Gradle,
Trivy) or reflect the Spring Boot 4 line (`spring-boot-starter-opentelemetry`).
No message broker, cache, second database, or vector store is added
(Section 25).

### 19.3 Version policy

The policy distinguishes the kinds of version:

1. **Runtime / platform versions** (JDK, Node.js, Python, PostgreSQL): pinned to a
   `major.minor` in one authoritative place each — the Gradle toolchain and
   Docker base image for the JDK, `.nvmrc` / `package.json` `engines` and the
   Docker image for Node, `pyproject.toml` `requires-python` and the Docker image
   for Python, the Docker image tag and Testcontainers for PostgreSQL. Upgraded
   deliberately, with a changelog entry.
2. **Java dependencies:** rely on the **Spring Boot BOM / dependency management**
   for every library it manages (Spring, Jackson, JUnit, Testcontainers,
   Micrometer, the JDBC driver, …). Do **not** manually pin Spring transitive
   dependencies. Declare explicit versions only for libraries the BOM does not
   manage (e.g. Spring AI, Flyway if not aligned, Spotless), and centralize those
   in the Gradle **version catalog** (`libs.versions.toml`).
3. **Python dependencies:** declared with compatible-range constraints in
   `pyproject.toml`; the exact resolved graph is pinned in a committed
   **`uv.lock`**. CI installs from the lockfile.
4. **Frontend dependencies:** semver ranges in `package.json`; the exact graph
   pinned in a committed **lockfile** (`pnpm-lock.yaml` or `package-lock.json`).
   CI installs with `--frozen-lockfile` / `npm ci`.
5. **Updates:** **Dependabot** (or Renovate) opens update PRs across all
   ecosystems; each must pass the full CI pipeline (including integration and
   contract tests) before merge. Spring Boot minor upgrades are handled as a
   deliberate, reviewed PR roughly every 6–12 months (Section 4.1).
6. **License hygiene:** prefer actively maintained libraries under permissive
   OSS licenses; a CI license check flags a non-permissive transitive dependency
   before an open-source release.

---

## 20. Security Baseline

`docs/02-functional-spec.md` sets no authentication for the personal-use MVP
(R14). Security still matters at the boundaries.

### 20.1 MVP posture

- **Network:** backend and agents bind to **localhost** by default; the database
  is not exposed outside the Compose network. Documented as a personal, local
  deployment.
- **No auth**, but the API is designed so that an auth layer can be added at the
  inbound boundary later without touching application/domain code
  (`docs/01-product-spec.md` deferred: authentication).
- **Secrets:** never in source; `.env` git-ignored; CI secrets scoped
  (Section 15.3).
- **Input validation:** all API inputs validated at the boundary; all Python
  responses schema-validated before use (Section 8.3).
- **LLM output is untrusted input:** structured, validated, and never used to
  build SQL, file paths, shell commands, or outbound requests without validation
  (`CLAUDE.md` Section 24).

### 20.2 Source fetching (SSRF and content safety)

Signal Engine fetches remote content, which is a security-relevant operation:

- Outbound fetches go through a **collector adapter** that can enforce: allowed
  schemes (`https`, and `http` only if explicitly configured), size limits,
  content-type checks, redirect limits, and a timeout.
- Protection against **SSRF** to internal addresses (block private/loopback/
  link-local ranges unless explicitly allowed) is a **required consideration**;
  the concrete policy is specified with the connectors in `08-ingestion.md`.
- Parsers treat fetched content as hostile input (no entity expansion, bounded
  recursion, no active content execution).

### 20.3 Dependencies and supply chain

- Dependency vulnerability scanning + Dependabot in CI (Section 18.1).
- Pinned versions and lockfiles; SBOM generated at release (Section 18.3).
- Container images built from pinned, minimal bases.

### 20.4 Data

- The database holds collected public information, the user's interests, and
  system activity — no credentials of third parties, no payment data.
- Backups (if used) are the user's responsibility and are documented, not
  automated.

### 20.5 Out of scope now

Authn/authz, multi-tenant isolation, rate limiting, WAF, transport hardening for
a public deployment — all deferred with the multi-user/public-deployment
questions.

---

## 21. Extensibility and Plug-and-Play Architecture

Concrete "how to extend" recipes, tied to `CLAUDE.md` Sections 11, 12, 29.

### 21.1 Add or replace a source

- Add configuration for the source (name/label, reference, type). No code change
  if the source **type** already has a connector.
- The source becomes eligible for collection; no change to normalization, dedup,
  AI processing, signals, search, alerting (`docs/02-functional-spec.md` R22).

### 21.2 Add a new source type

- Implement the `SourceCollector` port for the new type in infrastructure.
- Register it in the composition root, keyed by type.
- Nothing else changes. Add integration tests for the new adapter.

### 21.3 Add or swap an LLM provider

- Implement the provider adapter for the `LlmChatProvider` / `EmbeddingProvider`
  port.
- Add provider configuration; point one or more capabilities at it.
- No change to capability services, application logic, or the domain
  (`CLAUDE.md` Section 13). Business code contains no `if provider == …`.

### 21.4 Change the model for a capability

- Change configuration only (provider id / model id / params / prompt version).
- A model that changes vector dimension requires a documented re-embedding
  migration (`07-rag.md`).

### 21.5 Add, replace, or remove an AI capability/agent

- A capability is a contract + a Python handler + (optionally) a Java port.
- Adding one: define the versioned contract, implement the handler, add the
  Java-side port + adapter + call site in the relevant pipeline stage.
- Removing one: remove the call site and the handler; the contract is retired by
  version. No "God agent" — each capability keeps one responsibility
  (`CLAUDE.md` Section 12).

### 21.6 Split the Python service

- Because capabilities are stateless and contract-addressed, the single agent
  service can be split into several (e.g. by model size or hardware need) by
  changing the base URL(s) in configuration and routing per capability. Contracts
  are unchanged. This is an option, not an MVP action.

### 21.7 Add authentication later

- Introduced at the inbound adapter layer (a filter/interceptor) plus a user
  concept; application and domain layers are unaffected for existing single-user
  behavior.

---

## 22. Technical Constraints

1. The domain layer has **no** dependency on Spring, JPA, HTTP, the LLM SDK, or
   any provider.
2. Python agents **never** write business tables and hold **no** processing
   state.
3. A Python or LLM call is **never** executed inside an open database
   transaction.
4. No provider or model name appears outside configuration and provider adapters.
5. The frontend **never** connects to PostgreSQL.
6. LLM output consumed by business logic is **always** schema-validated first.
7. All schema changes go through Flyway; applied migrations are never edited.
8. Every environment-specific value is configuration; no secret is committed.
9. No message broker, no second database, no separate vector store, no
   orchestration platform, no cache tier in the MVP (Section 25).
10. Every significant technical decision is recorded (Section 23; ADRs as they
    mature).
11. The MVP runs as a **single backend instance**; no multi-node coordination is
    assumed or required.
12. No product scope is added here. Signal taxonomies, source-authority
    taxonomies, prediction, forecasting, opportunity scoring, recommendation
    engines, and trend engines are **not** reintroduced (`docs/01-product-spec.md`
    Section 9).

---

## 23. Technical Decisions

Treated as **agreed** for the project (from the stated direction and this
document):

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Backend is **Java 25 LTS + Spring Boot 4.1.x** (Spring Framework 7): backend, orchestrator, API, scheduler, persistence owner. (Java 21 LTS is an accepted fallback.) | Agreed direction; Spring Boot 3.5 is EOL (2026-06-30), so the 4.x line is the current generation; Java 25 gives the longest support runway with first-class Spring Boot 4.1 support |
| D2 | **PostgreSQL 17** is the single source of truth; the backend owns all business persistence and transactions | Agreed direction; PostgreSQL 17 has full, verified tooling support (Flyway, Testcontainers, driver, Docker images) where the newest major (18) still draws "not tested" warnings from some tools |
| D3 | Python provides stateless, specialized AI/NLP capabilities behind explicit contracts; **Python 3.13** | Agreed direction; 3.13 has the broadest verified library support for a new service and is in maintenance until 2029 |
| D4 | Java↔Python communication is synchronous HTTP/JSON with versioned request/response contracts, schema-validated both sides | Request/response interaction; no broker requirement |
| D5 | An LLM provider abstraction isolates Ollama/`gpt-oss:20b`; providers and models are configuration. The Java side implements the LLM ports with **Spring AI 2.0.x** (Boot-4-compatible GA), fully isolated behind our ports | Agreed direction; provider independence; a stable Boot-4 Spring AI release now exists |
| D6 | Vector search uses **PostgreSQL 17 + pgvector 0.8.x**, not a separate vector database | Agreed direction; avoid infrastructure |
| D7 | Clean Architecture / Ports and Adapters with an inward dependency rule | `CLAUDE.md` |
| D8 | Deterministic code for deterministic decisions; AI only for semantic decisions; the signal *state transition* is deterministic in Java | Central principle |
| D9 | Explicit, persisted processing states; idempotent stages keyed by deterministic item identity + content hash | Traceable, restart-safe processing |
| D10 | Bounded retries with exponential backoff + jitter; explicit timeouts; retryable vs non-retryable taxonomy | Resilience without hidden loops |
| D11 | Structured logging with a correlation/processing id; OpenTelemetry tracing across Java→Python→LLM via `spring-boot-starter-opentelemetry` (Micrometer + OTLP) and the OpenTelemetry Python SDK | Basic operational visibility, no dashboard |
| D12 | **Flyway** SQL migrations; **Testcontainers 2.x** integration tests against real PostgreSQL 17 + pgvector | Reproducible schema; realistic tests |
| D13 | Docker Compose for a one-command local full-stack system; Ollama is part of the default Compose stack (started by the normal `docker compose up`, no profile flag), providing embeddings (`embeddinggemma`) — a host Ollama remains a supported alternative. The generative LLM is **NVIDIA Build**, not Ollama (Section 17.2; `docs/adr/0006`) | Contributor onboarding |
| D14 | GitHub Actions: PR validation (build, lint, unit, integration, contract, security), main image build; releases as an evolution. Security = **Trivy + Dependabot + CodeQL** (complementary, not duplicated) | Practical open-source CI/CD |
| D15 | **React 19 + TypeScript 6.x + Vite 8** frontend consuming only `/api/v1`; typed client generated from OpenAPI; no direct DB access; **no state-management library** | Agreed direction; contract safety; MVP simplicity |
| D16 | No authentication in the MVP; localhost binding; auth addable at the inbound boundary later | `docs/02-functional-spec.md` R14 |
| D17 | LLM call-count is not prematurely minimised; multiple calls allowed when they improve quality, none added without purpose | Local-LLM MVP; quality first |
| D18 | Prompts are versioned assets; prompt/model/provider metadata is returned in every capability response | Traceability; evaluation-ready |
| D19 | **Java build tool: Gradle 9.x (Kotlin DSL)** with a version catalog | Stable, Java-25-ready, first-class Spring Boot 4 support; Maven 4 is still RC |
| D20 | **Persistence: Spring Data JDBC** (no JPA/Hibernate); hand-written SQL for vector queries | Straightforward relational data, explicit boundaries, cleaner Clean-Architecture fit |
| D21 | **Python project/dependency manager: `uv`** with a committed `uv.lock` (no Poetry, no pip-tools) | One fast, widely-adopted tool covering envs, resolution, locking, running |
| D22 | **Java dependency versions come from the Spring Boot BOM**; explicit pins only for unmanaged libraries, centralized in the Gradle version catalog | Avoids manually pinning Spring transitives; coherent upgrades |
| D23 | **Spring Boot minor upgrades** are tracked and applied every ~6–12 months as reviewed, CI-gated PRs | Spring Boot has no LTS; ~12-month support per minor |

**Proposed defaults still open** (technical, not product; changeable without a
product decision): embedding model (T3), pgvector index type/params (T7),
frontend served by backend vs own container (T8), retry/timeout numbers (T9),
coverage threshold (T10), main-branch image push policy (T11),
telemetry export target for local runs (T14), pnpm vs npm (T23), move to
PostgreSQL 18 (T20), TypeScript 7 migration (T22).

---

## 24. Open Technical Questions

These need a decision during or shortly after this stage. Several depend on
**open product/functional questions** and must not be resolved by inventing the
product answer.

The 2026-09-04 version/compatibility research settled several questions that were
previously open — recorded as decisions in Section 23:

- **T1 (Java version) → settled:** Java 25 LTS, Java 21 LTS fallback (D1).
- **T2 (Python framework + lock tool) → settled:** FastAPI + Pydantic v2 +
  Uvicorn; `uv` as the single dependency manager (D3, D21).
- **T4 (Spring AI) → settled:** adopt Spring AI 2.0.x behind our ports (D5).
- **T5 (Gradle vs Maven) → settled:** Gradle 9.x, Kotlin DSL (D19).
- **T6 (Spring Data JDBC vs JPA) → settled:** Spring Data JDBC (D20).

Still open:

| ID | Question | Depends on | Proposed direction |
|----|----------|-----------|--------------------|
| T3 | Which local **embedding model**, and vector dimension | `07-rag.md`; quality trials | Pick a small, well-supported local embedding model; make it configurable; document re-embedding. *Task 8.3A benchmark (real, 6 local models): provisional `embeddinggemma`/768, fallback `snowflake-arctic-embed2`/1024. Task 8.3B wired 768 into the schema (`vector(768)`, migration V11) as the provisional column. Task 8.3C ran the model through the full query→pgvector→Top-K path over the 8.3A corpus (Recall@1 0.958, everything else 1.000); still open pending a real-content re-run — `docs/07-rag.md` Section 22, 23, 24.* |
| T7 | pgvector index type and parameters | corpus size, recall trials | HNSW default; tune after real data. *Task 8.3B: still open. No ANN index created; distance metric chosen as **cosine**; the `vector(768)` column is ready for a `USING hnsw` index in a later migration. Task 8.3C: still open — `PgVectorRetriever` uses an exact cosine (`<=>`) scan on purpose, as the reproducible baseline for the eventual exact→ANN comparison — `docs/07-rag.md` Section 23, 24.* |
| T8 | Frontend served by backend vs its own container | deployment preference | Backend serves static assets in the MVP |
| T9 | Concrete retry counts, backoff, and timeout values | load/latency observation; source retry policy is **product Q10** | Use the proposed defaults in Section 13; tune with telemetry |
| T10 | Coverage threshold and where it gates | codebase maturity | Set (~70% domain+application) once code exists |
| T11 | Does `main` push container images or only tags | release strategy | SHA-tagged images on `main`; versioned images on release |
| T12 | Split Python into multiple services now or later | hardware/model needs | One service now; split is config-only later |
| T13 | Real-time UI updates (polling vs SSE/WebSocket) | UX need; alert channel is **product Q19** | Polling in the MVP |
| T14 | Telemetry export target for local runs (logs only vs local collector) | contributor tooling | Logs + metrics endpoint by default; optional local OTel collector in Compose |
| T15 | Near-duplicate similarity **mechanism** is fixed (embeddings + threshold); the **threshold/criteria** are **product Q12** | product Q12 | Configurable threshold; do not hardcode a cutoff |
| T16 | How the **signal decision** consumes the importance assessment (pure pass-through vs Java-side guardrails) | signal-selection criteria = **product Q4** | Keep Java transition deterministic and thin until Q4 is decided |
| T17 | Manual "collect now" endpoint and manual reprocessing controls | **product Q8, Q25** | Provide the technical hooks; expose endpoints only when the product decides |
| T18 | Activity-history retention/purge job | **product Q22** | Schema allows a purge job; not implemented until a retention policy exists |
| T19 | Multi-language processing path | **product Q24** (future) | English path only now; keep language a first-class field so a branch can be added |
| T20 | Move from **PostgreSQL 17 to 18** | Flyway certifying PostgreSQL 18; ecosystem catch-up | Stay on 17 for the MVP; move to 18 once tooling warnings clear — low-risk, no schema impact |
| T21 | Adopt additional **Testcontainers 2.x modules** as the Spring Boot 4 / Testcontainers 2.x integration matures | Testcontainers module stability | Use only the PostgreSQL module now (stable); assess others case by case |
| T22 | Migrate the frontend to **TypeScript 7** (native compiler, 8–12× faster) | `typescript-eslint` and Vite plugin support for TS 7's programmatic API (expected TS 7.1) | Start on TypeScript 6.x; migrate when the lint/build toolchain certifies TS 7 |
| T23 | Frontend package manager: **pnpm vs npm** | contributor preference | pnpm (speed, strict resolution); npm acceptable |

---

## 25. Rejected / Deferred Technologies and Why

| Technology | Status | Reason |
|------------|--------|--------|
| **Kafka / RabbitMQ / other message broker** | Rejected for the MVP | The Java↔Python interaction is synchronous request/response; the internal pipeline is driven by a scheduler + a persisted work table. A broker adds operational cost with no matching requirement. Revisit only if throughput or decoupling needs prove it. |
| **Redis / dedicated cache tier** | Rejected for the MVP | No demonstrated hot path. PostgreSQL and in-process handling are sufficient at personal-user scale. Caching identical LLM prompts can be added in-process if evidence shows value. |
| **Separate vector database (e.g. a dedicated ANN engine)** | Deferred | pgvector meets MVP retrieval needs and keeps embeddings transactional with their source rows. A dedicated engine is a future option at large corpus size. |
| **Second database / polyglot persistence** | Rejected for the MVP | One PostgreSQL covers relational + vector needs. Multiple stores add consistency and operational burden. |
| **Kubernetes / service mesh / API gateway** | Rejected for the MVP | Single-node, personal deployment. Docker Compose is enough. |
| **Microservice decomposition** | Deferred | The system is a modular backend + one AI sidecar. Capabilities can be split later without contract changes; doing it now would be premature. |
| **Workflow orchestration platform (e.g. a DAG engine)** | Rejected for the MVP | The pipeline is a short, linear, item-scoped sequence with explicit persisted states. An orchestration platform is disproportionate. |
| **Full observability stack (hosted metrics + tracing backend + dashboards + alerting)** | Deferred | `docs/01-product-spec.md` Section 8 keeps this for later. The MVP emits OTel telemetry and structured logs and exposes a metrics endpoint. |
| **AI evaluation subsystem / golden-answer regression suite** | Deferred | `docs/01-product-spec.md` keeps evaluation for later. Capability responses already carry the metadata needed to add it. |
| **Cloud LLM provider as the default** | Deferred | Local-first for privacy, cost, and offline use. The provider abstraction makes a cloud provider a configuration + adapter change later. |
| **Reranking model in search/RAG** | Deferred | Explicitly not an MVP requirement (`docs/01-product-spec.md` Section 8). |
| **WebSocket/SSE real-time transport** | Deferred | Polling is adequate for the MVP UI; the alert delivery channel is an open product question. |
| **Multi-node scheduling / distributed locks** | Deferred | The MVP runs one backend instance. A DB-based lock can be added if multi-instance is ever needed. |

---

## 26. Final Validation

### 26.1 Stack compatibility review (versions verified 2026-09-04)

**Java line — coherent:**
Java 25 LTS → Spring Boot 4.1.x → Spring Framework 7.0.x → Spring AI 2.0.x
(built for Spring Boot 4.1 / Framework 7) → Spring Data JDBC (Spring Boot BOM) →
PostgreSQL 17 (fully supported by Flyway, the JDBC driver, and Docker images) →
pgvector 0.8.x (supports PostgreSQL 13–18) → Testcontainers 2.x (via
`spring-boot-testcontainers`, PostgreSQL module stable). springdoc-openapi 3.x is
the Spring-Boot-4 line. `spring-boot-starter-opentelemetry` + Micrometer (1.x,
Spring Boot BOM) + OpenTelemetry Java is the supported Boot 4 observability path.
Gradle 9.x supports Java 25 and Spring Boot 4.

**Python line — coherent:**
Python 3.13 → FastAPI 0.14x → Pydantic v2 (2.13.x) → Uvicorn 0.52.x → httpx
0.28.x → `ollama` client / direct HTTP → pytest 9.x, Ruff 0.16.x, mypy 2.x, all
targeting 3.13 → `uv` 0.12.x manages and locks. OpenTelemetry Python SDK +
FastAPI/httpx instrumentation for tracing.

**Frontend line — coherent with one deliberate lag:**
React 19.2.x → TypeScript 6.x → Vite 8.x → Vitest (Vite-8-aligned major) →
ESLint 10.x + `typescript-eslint`. TypeScript 7.0 is GA but its programmatic API
is not stable until 7.1, so `typescript-eslint` / build plugins are not ready —
the project stays on TypeScript 6.x and tracks the migration (T22). No
state-management library.

**Rejected on compatibility grounds, not preference:**
- Spring Boot 3.x — EOL 2026-06-30.
- Spring AI 1.1.x — targets Spring Boot 3.5, incompatible with the Boot 4 baseline.
- PostgreSQL 18 as the MVP version — Flyway still emits "not tested" warnings.
- TypeScript 7.x now — ecosystem tooling gap until TS 7.1.
- Maven 4 — still release-candidate; Gradle 9 chosen.
- Micrometer 2.0 — milestone only; the Spring-Boot-managed 1.x line is used.

### 26.2 Consistency with the source documents

- **`CLAUDE.md`:** Clean Architecture, SOLID, plug-and-play, provider
  independence, deterministic-vs-AI split, source grounding, hallucination
  control via structure (not prompts alone), no unapproved technologies, no
  secrets in source, incremental delivery, ADRs for maturing decisions,
  English-only — all reflected. The version update introduces **no new
  technology category**; every change is a version line, a settled either/or
  (uv, Gradle, Spring Data JDBC, Trivy), or the Spring Boot 4 equivalent of an
  existing choice. Each is justified (Section 4, Section 19).
- **`docs/01-product-spec.md`:** "stay up to date" scope preserved; the simple
  signal concept, curated replaceable sources, English-focused MVP, feedback and
  simple alerting in scope, evaluation/observability deferred, and the
  out-of-scope list (forecasting, trend/opportunity/recommendation engines) are
  all respected. No product scope added.
- **`docs/02-functional-spec.md`:** the functional flow, the two-question
  relevance→importance model, provenance rules, "never silently drop", basic
  activity visibility, and the open questions Q1–Q25 are all carried through;
  technical choices that depend on an open functional question are marked and the
  question is preserved (Section 24, T9/T15/T16/T17/T18/T19).
- **Java owns business persistence and transactions:** D2, D20, Section 6.4,
  Constraint 2–3. Unchanged by the version update.
- **Python agents remain specialized and stateless:** Section 7, Constraint 2,
  Section 21.5 ("no God agent"). Unchanged.
- **LLM/provider replacement is possible:** Section 9.2, D5, Section 21.3–21.4,
  Constraint 4. Adopting Spring AI 2.0.x does **not** weaken this — it is the
  isolated implementation of `LlmChatProvider` / `EmbeddingProvider` and never
  appears above the infrastructure layer.
- **Error handling, logging, testing, CI/CD covered:** Sections 13, 14, 16, 18.
- **No unnecessary infrastructure introduced:** Section 25; Constraint 9. The
  version update adds nothing from the "what not to introduce" list.
- **Unresolved PRODUCT questions remain unresolved:** collection cadence, signal
  selection criteria, near-duplicate threshold, alert channel, activity
  retention, and multilingual behavior are still carried as functional questions
  (Section 24 T9/T15/T16/T17/T18/T19); the version research did not touch them.
- **Newly settled TECHNICAL decisions:** Java 25 + Spring Boot 4.1 (D1),
  PostgreSQL 17 (D2/D6), Python 3.13 (D3), Spring AI 2.0.x adopted (D5),
  Gradle 9 (D19), Spring Data JDBC (D20), uv (D21), BOM-driven Java versions
  (D22), Spring Boot upgrade cadence (D23). Remaining technical questions:
  Section 24 (T3, T7–T14, T20–T23).
