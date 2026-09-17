# Signal Engine

Signal Engine is an open-source, AI-powered intelligent information monitoring
platform. It collects information from multiple curated sources, normalizes it,
removes noise and duplicates, analyzes it with AI to identify relevant
**signals**, stores the resulting knowledge, and makes it searchable and usable
through retrieval-augmented generation (RAG).

```
Raw information → processing & analysis → relevant information → signals
→ searchable knowledge → source-grounded AI insights
```

It is **not** a chatbot. It is an information-processing and intelligence
platform with ingestion, normalization, deduplication, classification, relevance
analysis, summarization, knowledge storage, semantic search, RAG, specialized AI
agents, AI evaluation, and observability.

## Project status

Signal Engine is being built **incrementally, one decision at a time**. The
`docs/` directory is the **source of truth**; the code follows it.

Implementation-level decisions the specs delegate are recorded as ADRs under
`docs/adr/`. Work follows the formal phases in `docs/11-roadmap.md`. **RAG
Tasks 8.1–8.6** are implementation tasks *inside* Phases 7–8, not a separate
numbering scheme.

| Phase | Name | Status |
|---|---|---|
| 0 | Documentation / Decision Baseline | **Complete** — `docs/01`–`11`, reviewed as one set |
| 1 | Project Foundation | **Complete** — backend / agents / frontend skeletons, Docker Compose, dependency + version management, CI, health checks, `/api/v1` conventions, OpenAPI |
| 2 | Database / Persistence Foundation | **Complete** — Flyway migrations V1–V9, Spring Data JDBC ports + adapters (`docs/adr/0001`, `0002`) |
| 3 | Backend Core / Contracts | **Complete** — application use-case ports, the `/api/v1` business API (RFC 9457, OpenAPI), the Java↔Python capability contract + transport (`docs/adr/0003`, `0004`, `0006`) |
| 4 | Source Collection / Ingestion | **Complete (representative scope)** — one SSRF-guarded HTTP connector behind a `SourceCollector` port (scheme + resolved-address checks, bounded redirects/response size, validated-address pinning against DNS rebinding), deterministic normalisation + exact deduplication, provenance, processing state, idempotency (`docs/adr/0005`, `0017`). Invoked explicitly — no scheduler (cadence is Q9, open); final source list/types open (Q1) |
| 5 | Semantic Deduplication / Relevant Information | **Complete** — the `near-duplicate` capability + Java-owned grouping into Relevant Information (`docs/adr/0007`); boolean verdict, no numeric threshold (Q12 / T15 open) |
| 6 | Importance / Signal / Summary | **Complete** — the `relevance` / `importance` / `summarize` capabilities, deterministic Signal creation, a source-grounded Summary, migration V10 (`docs/adr/0008`); classification folded into `relevance` |
| 7 | Knowledge Base / Embeddings | **Complete** — the framework-free RAG core, semantic chunking, the embedding contract + a real local-model benchmark, and pgvector passage-index persistence + indexing pipeline (migration V11) — Tasks 8.1–8.3B, `docs/adr/0009`–`0012` |
| 8 | Semantic Search / Grounded Q&A | **Complete** — retrieval → context assembly → grounded generation → grounding validation → deterministic evaluation, composed as a `RagPipeline` and exposed through the `/api/v1/search` and `/api/v1/questions` REST endpoints (Tasks 8.3C–8.6, `docs/adr/0013`–`0016`); an item confirmed relevant is now indexed into `rag_passage`/`rag_passage_embedding` automatically, via `KnowledgeBaseIndexer` called from `DefaultProcessRelevantInformationUseCase` — no scheduler, no backfill of records processed before this wiring existed |
| 9 | Frontend | **Complete for its scope (Tasks 1–7 + Task 9 polish/integration).** Typed API client generated from the backend's OpenAPI document (`frontend/src/api/`, `npm run generate:api`). Screens implemented: Sources and Interests (`frontend/src/sources/`, `frontend/src/interests/`) — list, create, edit, enable/disable, no removal (the backend has none, by documented open decision); Signals (`frontend/src/signals/`) — newest-first list via `GET /api/v1/signals`, with a Relevant Information / provenance drill-down and, in that same detail view, feedback — mark a signal relevant or not relevant via `POST /api/v1/signals/{signalId}/feedback`, append-only (no edit/withdraw, per the backend contract), with the control replaced by a plain acknowledgment once the signal's state reflects a recorded verdict; Search (`frontend/src/search/`) — a query form over `POST /api/v1/search`, rendering source-grounded passages in retrieval order with score, provenance, and an original-source link; Q&A (`frontend/src/qa/`) — a single-question form over `POST /api/v1/questions`, showing the grounded answer with citations, or the explicit "not enough information" outcome when `answered=false`; Activity (`frontend/src/activity/`) — a read-only, newest-first feed via `GET /api/v1/activity`. The Task 9 polish pass added the Retry action missing from the Sources/Interests error states and merged duplicated provenance markup (Search/Q&A) into one shared component. **Alerts UI does not exist** — Task 8 found no alert backend capability at any layer (no domain object, migration, repository, use case, controller, or OpenAPI schema) and is deferred to Phase 10 |
| 10 | Alerts | Not started — gated on Q19 (channel) and Q20 (retry); the backend capability itself does not exist yet (confirmed by Phase 9 Task 8) |
| 11 | Evaluation / Hardening | Not started — the systematic end-to-end evaluation + security pass |

**Current position.** The backend runs the pipeline source → normalise →
deduplicate → relevance (→ index into the knowledge base) → importance →
Signal → summary, and a RAG pipeline (retrieve → assemble context → grounded
answer → validate → evaluate) is implemented, tested, and reachable via
`/api/v1/search` and `/api/v1/questions`. The frontend covers all of that
(Sources, Interests, Signals with feedback, Search, Q&A, Activity). **Phase 9
is complete for its scope. Next: Phase 10 — Alerts**, starting with the
backend capability that Phase 9 Task 8 confirmed does not exist yet.

**Not built yet:** a collection scheduler, alerts (backend or frontend — no
alert domain object, endpoint, or notification mechanism exists), and the
systematic evaluation / hardening pass. Advanced RAG (reranking, hybrid
retrieval, query rewriting, context compression, an LLM-judge) is explicitly
deferred. A one-off backfill for relevant information processed *before* the
indexing wiring existed is not built — only newly processed items are indexed.
Also open: **Q15** — no code path ever transitions a signal into `REVIEWED`
(a defined `SignalState` that nothing sets); dismissed/kept signals stay
visible in the list rather than being hidden, which is what's built, not a
documented resolution.

**Provisional, not final:** `embeddinggemma` / 768 as the embedding model and
dimension (T3); the exact pgvector cosine scan with no ANN index as the retrieval
baseline (T7); a boolean near-duplicate verdict with no numeric threshold
(Q12 / T15); a thin, deterministic Signal transition (Q4 / T16); and the summary
length (Q14). See `docs/11-roadmap.md` Section 2.1 and Section 18, and
`docs/03-technical-spec.md` Section 24.

## Non-goals / current limitations

Signal Engine's MVP is deliberately scoped. At minimum:

- **Single-user, no authentication.** There is exactly one user role and no
  login; the API is not designed to be exposed beyond localhost/trusted-network
  access as-is (`docs/02-functional-spec.md` R14, `docs/10-security.md`).
- **No collection scheduler.** Source collection is invoked explicitly; there
  is no cron/background job that collects on a cadence yet (Phase 10+ scope,
  `docs/11-roadmap.md`).
- **Alerts are not implemented.** No alert domain object, endpoint, or
  notification mechanism exists yet — see "Not built yet" above and Phase 10.
- **No forecasting, trend analysis, correlation, or opportunity/recommendation
  scoring.** Signal Engine presents signals; it does not predict outcomes or
  recommend actions (`docs/01-product-spec.md` Section 9).

This list is not exhaustive; `docs/11-roadmap.md` Section 16 ("Explicitly
Deferred Work") is the authoritative list of everything intentionally out of
scope for the MVP.

## Repository layout

| Path | What it is |
|------|------------|
| `docs/` | Product, functional, technical, architecture, data-model, AI, RAG, ingestion, evaluation, security, and roadmap documents. The source of truth. |
| `docs/adr/` | Architecture Decision Records for implementation-level decisions the specs delegate (`CLAUDE.md` Section 22). |
| `backend/` | Java 25 (21 fallback) + Spring Boot 4.1 service — API, orchestration, persistence coordination. Clean Architecture layers: `domain`, `application`, `interfaces`, `infrastructure`. Flyway SQL migrations under `src/main/resources/db/migration`. |
| `agents/` | Python 3.13 + FastAPI service — stateless AI/NLP capabilities behind explicit versioned contracts: `echo`, `near-duplicate`, `relevance`, `importance`, `summarize`, `semantic-chunk-boundary`, `embed`, `answer`. |
| `frontend/` | React 19 + TypeScript + Vite single-page app — Phase 1 skeleton; talks only to `/api/v1`. |
| `docker-compose.yml` | One-command local system: `db` (PostgreSQL 17 + pgvector), `backend`, `agents`, `frontend`, `ollama`, and the one-shot `ollama-pull`. |

The architecture and its boundaries are described in `docs/04-architecture.md`.

**RAG implementation.** The framework-independent RAG core (chunking, context
assembly, embedding contract, retrieval, generation, indexing, evaluation)
lives under `backend/src/main/java/org/signalengine/rag/`, with its Spring/
pgvector/AI-capability adapters under
`backend/src/main/java/org/signalengine/infrastructure/rag/`. The Python side
provides the `embed`, `semantic-chunk-boundary`, and `answer` capabilities
(`agents/`, see the repository layout above). The design is documented in
`docs/07-rag.md`.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 25 LTS (target) / **21 LTS** (accepted fallback) | The Gradle toolchain currently pins 21. |
| Node.js | 22 LTS or 24 LTS | `frontend/.nvmrc` pins 24. |
| Python | 3.13 | Provisioned automatically by `uv`. |
| `uv` | current | Python project/dependency manager. |
| Docker + Docker Compose | current | For the local system, the PostgreSQL database, and the Testcontainers integration tests. |

Gradle is provided by the committed wrapper (`backend/gradlew`); no separate
install is needed.

## Running

### Whole system (Docker Compose)

```bash
cp .env.example .env        # non-secret local defaults
docker compose up --build   # or: make up
```

Published ports (bound to `127.0.0.1` only):

| Service | URL |
|---------|-----|
| Backend | http://127.0.0.1:8080 — health at `/actuator/health`, Flyway status at `/actuator/flyway`, API at `/api/v1`, OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html` |
| Agents | http://127.0.0.1:8100 — health at `/health`, OpenAPI at `/openapi.json`, docs at `/docs` |
| Frontend | http://127.0.0.1:5173 |
| PostgreSQL | `127.0.0.1:5432` — PostgreSQL 17 + pgvector; the backend runs the Flyway migrations against it on startup |
| Ollama | `127.0.0.1:11434` (override with `OLLAMA_PORT` if a host Ollama already uses it) |

Ollama now starts with the normal `docker compose up` — no profile flag needed —
but only for **embeddings**: a one-shot `ollama-pull` service pulls the configured
embedding model (`OLLAMA_EMBEDDING_MODEL`, `embeddinggemma`) automatically via
Ollama's own API (`docker compose logs ollama-pull` to watch progress). The
**generative LLM is NVIDIA Build** (`AGENTS_LLM_PROVIDER=nvidia`,
`NVIDIA_BASE_URL=https://integrate.api.nvidia.com/v1`) — near-duplicate,
relevance, importance, summarization, and grounded Q&A all use it; set
`NVIDIA_API_KEY` in `.env` (never committed) for these to work. `gpt-oss:20b` is
no longer downloaded or required. Nothing waits on the embedding-model download:
backend/agents become healthy immediately, and ingestion's existing retry
behavior picks the embedding capability back up once the model is ready. A
**host Ollama** with a configured `OLLAMA_URL` remains a supported alternative
for embeddings (stop the `ollama`/`ollama-pull` services in that case).

Running the backend on the host needs a reachable PostgreSQL: start just the
database with `docker compose up -d db` first (or run the full stack).

**Initial source seeding.** A fresh system starts with no configured sources.
Once the backend is up, `make seed` (`scripts/seed-sources.sh`) registers a
curated starter set of sources via the public `POST /api/v1/sources` API — the
same way a user would add them. This is explicit and opt-in, never automatic:
the concrete source list remains an open product decision
(`docs/02-functional-spec.md` Q1), so nothing is seeded unless you run it.

### Individual sub-projects

```bash
# Backend
cd backend && ./gradlew bootRun

# Agents
cd agents && uv run python -m app.main

# Frontend
cd frontend && npm install && npm run dev
```

## Tests and quality checks

`make test` / `make lint` run everything. Without `make`:

```bash
# Backend — Spotless, unit/API tests, JaCoCo coverage (no Docker)
cd backend && ./gradlew spotlessCheck build
# Backend — Flyway migrations + schema against real PostgreSQL 17 + pgvector (needs Docker)
cd backend && ./gradlew integrationTest

# Agents — ruff, mypy (strict), pytest
cd agents && uv run ruff check . && uv run ruff format --check . && uv run mypy && uv run pytest

# Frontend — ESLint, tsc, Prettier, Vitest, build
cd frontend && npm run lint && npm run typecheck && npm run format:check && npm test && npm run build
```

CI (`.github/workflows/pr.yml`) runs the same checks on every pull request, plus a
Trivy vulnerability scan; CodeQL and Dependabot are configured separately.

## Configuration

All configuration is environment-variable driven (`docs/03-technical-spec.md`
Section 15). `.env.example` documents every key with non-secret local defaults;
copy it to `.env` (git-ignored) and adjust. No secret is ever committed.

## Contributing

Signal Engine is developed step by step, guided by `docs/`. Before changing
behavior, read the relevant document; if an implementation changes an
architectural decision, update the document (and add an ADR — `CLAUDE.md`
Section 22). The entire project is in English. See `CLAUDE.md` for the working
rules.

## License

Signal Engine is licensed under the [MIT License](LICENSE).
