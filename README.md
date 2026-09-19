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
| 4 | Source Collection / Ingestion | **Complete (representative scope)** — one SSRF-guarded HTTP connector behind a `SourceCollector` port (scheme + resolved-address checks, bounded redirects/response size, validated-address pinning against DNS rebinding), deterministic normalisation + exact deduplication, provenance, processing state, idempotency (`docs/adr/0005`, `0017`). Runs once in the background at each backend start — no scheduler (cadence is Q9, open); final source list/types open (Q1) |
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

## How Signal Engine works

Signal Engine turns collected information into three things: **Signals** worth
your attention, a searchable **knowledge base**, and **source-grounded answers**.
The backend runs this pipeline over the sources you have configured:

```
Configured sources
        ↓
Collection  (raw information items)
        ↓
Normalization
        ↓
Exact deduplication
        ↓
Near-duplicate grouping  (→ Relevant Information records)
        ↓
Relevance assessment
        ↓
Knowledge-base indexing
        ↓
Importance assessment
        ↓
Signal creation
        ↓
Source-grounded summary
        ↓
Search / grounded Q&A  (on demand, over the indexed knowledge base)
```

| Stage | Enters | What it does | Comes out | Deterministic or AI-assisted |
|---|---|---|---|---|
| Collection | An enabled source | Fetches the source through a single SSRF-guarded HTTP connector | Raw information items with provenance (source, original URL) | Deterministic |
| Normalization | A raw item | Cleans it into a consistent form | The item's normalized content | Deterministic |
| Exact deduplication | Normalized items | Detects repeats of identical content | Repeats marked as duplicates | Deterministic |
| Near-duplicate grouping | Deduplicated items | Decides whether items describe the same thing | Relevant Information records grouping the contributing items | AI-assisted (`near-duplicate` capability; boolean verdict) |
| Relevance assessment | A Relevant Information record | Judges relevance to your enabled interests and matches areas of interest | A reason and matched areas — or the item is set aside | AI-assisted (`relevance` capability) |
| Knowledge-base indexing | An item confirmed relevant | Splits it into passages, embeds them, stores them in PostgreSQL/pgvector | Searchable passages | Chunking is AI-assisted (`semantic-chunk-boundary`); embedding uses the configured embedding model (`embed`) |
| Importance assessment | A relevant item | Judges whether it is important enough to surface | An importance verdict | AI-assisted (`importance` capability) |
| Signal creation | An important item | Creates a Signal linked to its Relevant Information and source | A Signal (initial state `NEW`) | Deterministic |
| Source-grounded summary | A Signal | Summarizes the underlying source content | A Summary tied to the Signal | AI-assisted (`summarize` capability) |
| Search | A query | Embeds the query and retrieves the closest indexed passages (exact pgvector cosine top-K) | Passages with score and provenance | Deterministic retrieval |
| Q&A | A question | Retrieves passages, assembles a context, generates an answer, validates its citations | An answer with citations, or an explicit "not enough information" | Retrieval and grounding validation are deterministic; generation is AI-assisted (`answer` capability) |

### Concepts

- **Raw information** — what is collected from a configured source, kept with
  its provenance (source and original URL).
- **Relevant Information** — information that has passed the relevance
  assessment, grouped into one record when several raw items describe the same
  thing.
- **Signal** — *a piece of information that is relevant to the user's interests
  and/or important enough to bring to their attention.* A Signal is **not**
  every collected article: only relevant, important items become Signals.
- **Summary** — a source-grounded summary the system creates for each Signal. It
  is generated and stored as part of processing, but it is **not currently
  exposed**: there is no dedicated Summary REST endpoint, so the frontend does
  not display summaries as a field or view.
- **Knowledge base** — the passages of information confirmed relevant, indexed
  into PostgreSQL/pgvector so they can be retrieved semantically. An item that is
  relevant but not important is still in the knowledge base; it just is not a
  Signal.
- **Search** — retrieval from the indexed knowledge base; it does not call a
  generative LLM (it only embeds the query).
- **Q&A** — retrieval of relevant context, then an LLM-generated answer
  restricted to that context, with citations.

Four distinct concerns are kept apart: **ingestion** (collect → deduplicate →
group), **signal detection** (relevance → importance → Signal → summary),
**knowledge-base indexing** (passages + embeddings), and **retrieval and
generation** (Search and Q&A, on demand). Ingestion decides what belongs in the
knowledge base; RAG only makes it searchable (`docs/08-ingestion.md`,
`docs/07-rag.md`).

## Non-goals / current limitations

Signal Engine's MVP is deliberately scoped. At minimum:

- **Single-user, no authentication.** There is exactly one user role and no
  login; the API is not designed to be exposed beyond localhost/trusted-network
  access as-is (`docs/02-functional-spec.md` R14, `docs/10-security.md`).
- **No collection scheduler.** Collection runs once, in the background, each
  time the backend starts; there is no recurring job that collects on a cadence
  and no "collect now" trigger yet (`docs/11-roadmap.md`).
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
| `frontend/` | React 19 + TypeScript + Vite single-page app — Home, Sources, Interests, Signals, Search, Q&A, and Activity screens (see the [Frontend guide](#frontend-guide)); talks only to `/api/v1`. |
| `scripts/` | `seed-sources.sh` — the opt-in starter-source seeding script behind `make seed`. |
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

### What happens when the stack starts

`docker compose up --build` (`make up` runs it detached with `-d`) brings the
system up in this order:

1. **`db`** starts — PostgreSQL 17 with the pgvector extension — and must pass its
   health check.
2. **`agents`** (the Python AI capability service) starts and must become healthy.
3. **`backend`** starts once `db` and `agents` are healthy. It connects to
   PostgreSQL and **Flyway applies the database migrations** automatically,
   creating or updating the schema, including the `vector` tables used by RAG.
4. **`frontend`** starts after `backend` is started.
5. **`ollama`** starts as part of the default stack, used **only for embeddings**.
6. The one-shot **`ollama-pull`** service pulls the configured embedding model
   (`OLLAMA_EMBEDDING_MODEL`, currently `embeddinggemma`) into Ollama, then exits.
   Progress: `docker compose logs ollama-pull`.

The **generative** AI provider is **NVIDIA Build**, not Ollama; `NVIDIA_API_KEY`
is required for near-duplicate, relevance, importance, summarization, semantic
chunking, and grounded Q&A to work. Nothing waits on the embedding-model download:
backend and agents do not stay unhealthy while it runs. Calls to the AI
capabilities use bounded retries, but a record whose indexing already failed is
**not** re-indexed later (see [Troubleshooting](#troubleshooting)).

**Once the backend has started, it runs the ingestion pipeline exactly once, in
the background** (collect → group → process, over the sources that are configured
and enabled at that moment; `StartupIngestionRunner`). With no sources yet, this
is a no-op. There is **no scheduler**, no continuous monitoring, no automatic
source seeding, and no automatic historical backfill.

### First run

**1. Clone the repository**

```bash
git clone https://github.com/SlaouiSS/signal-engine.git
cd signal-engine
```

**2. Create the local environment file**

```bash
cp .env.example .env
```

`.env` is git-ignored. Keep secrets there only, never in committed files.

**3. Configure NVIDIA**

Set your own key in `.env`:

```
NVIDIA_API_KEY=<your NVIDIA Build API key>
```

NVIDIA Build provides the generative AI capabilities. Without a key, the AI
stages that need it cannot run.

**4. Start the stack**

```bash
docker compose up --build   # or: make up (detached)
```

This starts `db`, `agents`, `backend`, `frontend`, `ollama`, and `ollama-pull`.

**5. Wait for the services**

```bash
curl http://127.0.0.1:8080/actuator/health   # backend
curl http://127.0.0.1:8080/actuator/flyway   # applied migrations
curl http://127.0.0.1:8100/health            # agents
docker compose ps                            # db, backend, agents health
```

The frontend is at http://127.0.0.1:5173.

**6. Database initialization**

You do not create the schema yourself. PostgreSQL is provided by Docker Compose,
and the backend's Flyway migrations create and update the schema on every
startup. A "fresh database" therefore has the full schema but **no data**: no
sources, no interests, no information.

**7. Seed the initial sources**

```bash
make seed    # runs scripts/seed-sources.sh
```

This calls the public `POST /api/v1/sources` API to register the curated starter
sources (it never touches the database directly, and skips sources already
configured). Seeding is explicit and opt-in — it never happens automatically. You
can also add sources yourself on the Sources screen.

**8. Run collection and processing**

There is no "collect now" command or endpoint, and no scheduler. The only trigger
in the current implementation is the one-time background run at backend startup.
Because the backend ran that pass before you seeded, **restart the backend after
seeding** so it collects the sources you just configured:

```bash
docker compose restart backend
```

The run happens in the background after startup and depends on the network and
on the AI services, so results appear progressively, not instantly. To collect
again later, restart the backend again; each stage is idempotent, so repeated
runs do not duplicate items.

**9. What you should see**

- **Sources** lists the registered sources.
- As items are collected and processed, **Home** shows relevant information.
- **Signals** appears for items that are also assessed as important.
- Relevant items are indexed into the knowledge base, so **Search** and **Q&A**
  become useful once passages have been indexed.
- **Activity** records processing successes and failures, including indexing.

### Automatic vs explicit actions

| Action | Automatic? | Explanation |
|---|---|---|
| PostgreSQL startup | Yes | Docker Compose starts `db` (PostgreSQL 17 + pgvector) |
| Flyway migrations | Yes | The backend applies them on startup |
| Ollama startup | Yes | Part of the default Compose stack (embeddings only) |
| Embedding model pull | Yes | `ollama-pull` downloads `OLLAMA_EMBEDDING_MODEL` (`embeddinggemma`) |
| NVIDIA configuration | No | You must set `NVIDIA_API_KEY` in `.env` |
| Source seeding | No | You run `make seed` (or add sources in the UI) |
| Source collection | Once per backend start | `StartupIngestionRunner` runs collect → group → process in the background after startup; restart the backend to run it again |
| Knowledge-base indexing | Yes | Part of processing: an item confirmed relevant is indexed |
| Continuous / scheduled collection | No | No scheduler exists |
| Historical backfill | No | Records processed before the indexing wiring existed are not indexed |

### The database

For the normal Docker Compose flow you do **not** need PostgreSQL installed
locally: Compose provides PostgreSQL 17 + pgvector, published on
`127.0.0.1:5432`, with data kept in the `db-data` volume. The backend's Flyway
migrations own the schema, so do not create tables by hand. A PostgreSQL service
already running on the host's port 5432 conflicts with the Compose database
(see [Troubleshooting](#troubleshooting)).

### Individual sub-projects

```bash
# Backend
cd backend && ./gradlew bootRun

# Agents
cd agents && uv run python -m app.main

# Frontend
cd frontend && npm install && npm run dev
```

## Frontend guide

The frontend has seven screens, reached from the top navigation. Each one also
shows a short "What is this?" panel. Alerts are **not** implemented and have no
screen.

- **Home** — a quick view of recent relevant information, newest first, as the
  backend returns it. A record that has become a Signal carries a **Signal**
  badge and a "View details" action. It is not a generic analytics dashboard.
- **Sources** — the websites and feeds Signal Engine collects from. Add, edit,
  enable, or disable a source (sources cannot be deleted). Only enabled sources
  take part in collection, and collection is not continuously scheduled.
- **Interests** — the topics you care about, grouped under the fixed areas of
  interest. Add, edit, enable, or disable interests. Enabled interests take part
  in relevance assessment. This is not recommendation or personalization logic.
- **Signals** — the signals Signal Engine has produced, newest first, with their
  state. Opening one shows why it matters, its area(s), and its original
  source(s) with a link to each. Generated summaries are not shown here (no
  Summary endpoint exists yet). You can mark a signal **relevant** or **not
  relevant**; feedback is recorded once and cannot be changed.
- **Search** — searches the indexed knowledge base by meaning, not the open web.
  Each result is a passage with its score and provenance, including a link to the
  original source.
- **Q&A** — ask one question and get an answer generated only from passages
  retrieved from the knowledge base, with citations. If the knowledge base does
  not contain enough information, it says so instead of answering. It is not a
  general-purpose chatbot and keeps no conversation.
- **Activity** — a read-only, newest-first feed of recorded processing activity,
  including failures. It is an operational log, not a full observability
  dashboard.

## Data and knowledge flow

How one piece of information moves through Signal Engine:

```
Source
  ↓
Raw information item          (with provenance: source + original URL)
  ↓
Normalization
  ↓
Exact / near-duplicate handling
  ↓
Relevance assessment  ──→  set aside if not relevant
  ↓
Relevant Information ──→ Knowledge-base indexing (passages + embeddings)
  ↓                                   ↓
Importance assessment                 └──→ Search / Q&A
  ↓
Signal + Summary
```

The same underlying information can therefore be **presented** as a Signal (with
a summary and feedback) and, independently, **retrieved later** through Search and
Q&A, because it was indexed when it was confirmed relevant. A relevant item that
is not important enough to become a Signal is still searchable.

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

## Troubleshooting

- **Port 5432 already in use.** A PostgreSQL instance on the host can stop the
  Compose `db` service from binding `127.0.0.1:5432`. Stop the host instance, or
  set `POSTGRES_PORT` in `.env` to another host port (the backend reaches `db`
  over the Compose network, so it is unaffected).
- **`NVIDIA_API_KEY` missing.** The AI stages that use the generative provider
  (near-duplicate, relevance, importance, summarization, semantic chunking, Q&A)
  cannot run normally. Set the key in `.env` and recreate the containers.
- **Embedding model still downloading.** `ollama-pull` fetches `embeddinggemma`
  in the background (`docker compose logs ollama-pull`). Embedding calls use
  bounded retries, but indexing is attempted once per relevant record and a
  failure is only recorded in Activity, so a record processed before the model
  was ready may not be searchable.
- **No sources visible.** A fresh installation has none. Run `make seed`, then
  `docker compose restart backend` to collect them.

## Contributing

Signal Engine is developed step by step, guided by `docs/`. Before changing
behavior, read the relevant document; if an implementation changes an
architectural decision, update the document (and add an ADR — `CLAUDE.md`
Section 22). The entire project is in English. See `CLAUDE.md` for the working
rules.

## License

Signal Engine is licensed under the [MIT License](LICENSE).
