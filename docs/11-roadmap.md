# Signal Engine — Implementation Roadmap

Document ID: `11-roadmap.md`
Status: Living document — Phases 0–9 complete for their scope; Phase 10
(Alerts) is the next major implementation phase. See *Implementation status*
below (updated 2026-09-17). No open question is resolved by that update.
Depends on: `CLAUDE.md`, `docs/01-product-spec.md`, `docs/02-functional-spec.md`,
`docs/03-technical-spec.md`, `docs/04-architecture.md`, `docs/05-data-model.md`,
`docs/06-ai-agents.md`, `docs/07-rag.md`, `docs/08-ingestion.md`,
`docs/09-evaluation.md`, `docs/10-security.md`

This is the final documentation file in the Signal Engine documentation
phase. It is an **organization document**: it sequences the decisions already
made in `docs/01-10` into implementation phases. It introduces **no new
product feature**, resolves **no open question**, invents **no technical
choice**, and promotes **no deferred idea into MVP scope**. Every phase below
exists to answer one question — *what should be built first, and what does it
depend on* — using only what `docs/01-10` already approve.

---

## 1. Roadmap Principles

- **Build from stable foundations upward.** Persistence and contracts come
  before behavior that depends on them (Sections 4–6).
- **Keep one clear vertical path through the system.** A thin, complete path
  from source to signal to search is more valuable early than broad,
  incomplete coverage (Section 2).
- **Implement deterministic behavior before AI-dependent behavior where
  practical.** Collection, normalization, and exact deduplication are
  deterministic and testable without a model; they come before the AI stages
  that build on them (`docs/03-technical-spec.md` Section 3.3;
  `docs/08-ingestion.md` Section 2).
- **Establish contracts before integrating implementations.** The
  Java↔Python request/response contracts (`docs/03-technical-spec.md`
  Section 8) are fixed early so both sides can be built and tested against a
  stable shape.
- **Keep Java as business-state authority** throughout every phase
  (`docs/04-architecture.md` Section 3.2, 17).
- **Keep Python as a stateless AI capability layer** throughout every phase
  (`docs/06-ai-agents.md` Section 2).
- **Keep PostgreSQL as the single source of truth** throughout every phase
  (`docs/05-data-model.md` Section 2).
- **Keep source connectors replaceable** from the first connector onward
  (`docs/08-ingestion.md` Section 4).
- **Keep AI providers replaceable** from the first capability onward
  (`docs/06-ai-agents.md` Section 9).
- **Validate each phase before adding the next** — a phase is not "done" until
  its checkpoint (Section 20) is met.
- **Avoid premature infrastructure.** Nothing on the "explicitly deferred"
  list (Section 16) is introduced to make an earlier phase more convenient.
- **Do not implement unresolved decisions before they are explicitly
  decided.** Where a phase depends on an open question, that question is a
  gate (Section 18), not something the roadmap answers on the phase's behalf.

No additional principle is introduced beyond this list, and none of these
principles changes the architecture already approved in `docs/03-` and
`docs/04-technical`/`architecture` documents.

---

## 2. Implementation Strategy

```text
Project Foundation
        ↓
Database / Persistence Foundation
        ↓
Backend Core / Contracts
        ↓
Source Collection
        ↓
Normalization / Deduplication
        ↓
AI Processing
        ↓
Signal Creation
        ↓
RAG / Search / Q&A
        ↓
Frontend
        ↓
Alerts
        ↓
Evaluation / Hardening
```

This is a **conceptual order**, matching the dependency structure already
implied by `docs/03-technical-spec.md` Section 10.1 and
`docs/08-ingestion.md` Section 23. It does not mean each box must be 100%
complete before the next begins — the architecture supports incremental,
vertical implementation (Section 17) — but it fixes the order in which
**foundational** capability becomes available.

---

## 2.1 Implementation Status (updated 2026-09-17)

Phases 0–9 are complete for their scope, all built from the approved
documentation. The RAG work is broken into **Tasks 8.1–8.6**, which
are implementation tasks *inside* Phases 7 and 8 — **not** an alternative
project-phase numbering scheme.

| Phase | Name | Status |
|---|---|---|
| 0 | Documentation / Decision Baseline | **Complete** — `docs/01`–`11`; reviewed together as one set |
| 1 | Project Foundation | **Complete** — repository layout, the three sub-project skeletons, Docker Compose, dependency/version management, CI, health checks, `/api/v1` conventions, OpenAPI |
| 2 | Database / Persistence Foundation | **Complete** — Flyway migrations V1–V9, Spring Data JDBC ports + adapters (`docs/adr/0001`, `docs/adr/0002`) |
| 3 | Backend Core / Contracts | **Complete** — application use-case ports, the `/api/v1` business API (RFC 9457, OpenAPI), the Java↔Python capability contract + transport (`docs/adr/0003`, `docs/adr/0004`, `docs/adr/0006`) |
| 4 | Source Collection / Ingestion | **Complete for its validation scope** — one representative SSRF-guarded HTTP connector behind the `SourceCollector` port (scheme + resolved-address checks, bounded redirects and response size, validated-address pinning against DNS rebinding), deterministic normalisation + exact deduplication, provenance, processing state, idempotency (`docs/adr/0005`, `docs/adr/0017`); the ingestion pipeline runs once, in the background, each time the backend process starts (`StartupIngestionRunner`: collect → group → process); there is no recurring scheduler and no collect-now endpoint or other manual trigger (cadence = Q9 and manual trigger = Q8 / T17, open), and the final source list/types are still open (Q1) |
| 5 | Semantic Deduplication / Relevant Information | **Complete** — the `near-duplicate` capability + Java-owned grouping into Relevant Information (`docs/adr/0007`); a boolean "same underlying story?" verdict, no numeric threshold (Q12 / T15 open) |
| 6 | Importance / Signal / Summary | **Complete** — the `relevance` / `importance` / `summarize` capabilities, deterministic Signal creation, the source-grounded Summary, migration V10 (`docs/adr/0008`); the Java Signal transition is kept thin (Q4 / T16 open) and summary length is provisional (Q14 open); classification is folded into `relevance` (`docs/adr/0008`; `docs/06-ai-agents.md` Section 4.2) |
| 7 | Knowledge Base / Embeddings | **Complete** — the framework-free RAG core, semantic chunking, the embedding contract + a real local-model benchmark, and the PostgreSQL/pgvector passage-index persistence + indexing pipeline (migration V11) — Tasks 8.1–8.3B, `docs/adr/0009`–`0012`. `embeddinggemma` / 768 is **provisional** (T3); distance is cosine with no ANN index (T7). This phase delivered the indexing pipeline itself; it is wired to stored content in Phase 8, where an item is indexed the moment relevance assessment confirms it (see the Phase 8 row) — no backfill of records processed before that wiring existed (`docs/05-data-model.md` Section 16) |
| 8 | Semantic Search / Grounded Q&A | **Complete** — retrieval → context assembly → grounded generation → grounding validation → deterministic evaluation, composed as a `RagPipeline` and exposed through the `/api/v1/search` and `/api/v1/questions` REST endpoints, each backed by a thin application use case (Tasks 8.3C–8.6, `docs/adr/0013`–`0016`). Index population is wired: `DefaultProcessRelevantInformationUseCase` indexes an item into `rag_passage`/`rag_passage_embedding` through `KnowledgeBaseIndexer` the moment relevance assessment confirms it — no scheduler, no backfill of records processed before this wiring existed. Advanced RAG (reranking, hybrid retrieval, query transformation, context compression, an LLM-judge) remains deferred |
| 9 | Frontend | **Complete for its scope (Tasks 1–7 + Task 9 polish/integration; Task 8 Alerts intentionally deferred to Phase 10 — no alert backend capability exists yet).** Typed API client generated from the backend's OpenAPI document (`frontend/src/api/`, `npm run generate:api`). Seven screens implemented: Home (`frontend/src/home/`) — the default screen, a newest-first view of recent relevant information via the existing `GET /api/v1/relevant-information`, with a Signal badge and the Signals detail view when a record has become a Signal; Sources and Interests within the six fixed areas (`frontend/src/sources/`, `frontend/src/interests/`) — list, create, edit, enable/disable; no removal (the backend has none, by documented open decision — `ManageSourcesUseCase`/`ManageInterestsUseCase`); Signals (`frontend/src/signals/`) — newest-first list via `GET /api/v1/signals` (Task 3's backend-enabling addition), with a Relevant Information / provenance drill-down (source, original-source link) reached via the existing `GET /api/v1/relevant-information/{id}` and its `raw-information-items` sub-resource. No summary is shown — the backend exposes no Summary REST endpoint. Search (`frontend/src/search/`) — a query form over the existing `POST /api/v1/search`, rendering source-grounded passages in the backend's retrieval order with score, provenance, and an original-source link; no frontend ranking, filtering, or sorting. Q&A (`frontend/src/qa/`) — a single-question form over the existing `POST /api/v1/questions`, rendering the grounded answer with citations, or the backend's explicit "not enough information" outcome when `answered=false`; no conversation history, no frontend generation. Activity (`frontend/src/activity/`) — a read-only, newest-first feed via the existing `GET /api/v1/activity` (same bounded-list pattern as Signals); no detail drill-down, since the record already carries everything the backend exposes. Feedback — integrated into the Signals detail screen (`SignalDetail.tsx`, not a standalone screen) via the existing `POST /api/v1/signals/{signalId}/feedback`: a fixed relevant / not-relevant choice (the only verdicts the backend's `FeedbackVerdict` enum defines), shown only while the signal's state is `NEW`/`REVIEWED`; once feedback is recorded the signal's state becomes `KEPT`/`DISMISSED` (the only code path that sets those states) and the control is replaced by a plain acknowledgment — there is no edit/withdraw endpoint to build against, no free-text field (the backend accepts none), and no feedback-history view (`findBySignalId` exists on the repository but is not wired to any use case or controller). A Task 9 polish/integration pass reviewed navigation, loading/error/empty states, data safety, API usage, and accessibility across the six screens that existed at the time (Home was added afterwards): it added the missing Retry action on the Sources and Interests load-error states (present everywhere else) and extracted the identical provenance-rendering markup duplicated between Search and Q&A into one shared `ProvenanceDetails` component; no other concrete defect was found. Alerts UI does not exist — Task 8 is deferred to Phase 10, since no alert backend capability exists yet (see Phase 10 below) |
| 10 | Alerts | **Not started** — begins only once Q19 (alert channel) and Q20 (alert retry) are decided; these remain gates |
| 11 | Evaluation / Hardening | **Not started** — the dedicated, systematic end-to-end evaluation and security-hardening pass; distinct from the deterministic RAG-core `Evaluator` delivered in Task 8.6 (`docs/adr/0016`), which is a contract-level component, not the deferred AI evaluation subsystem/platform (Section 16) |

**Current position.** Phases 0–9 are complete for their scope: Phase 8's RAG
pipeline (Tasks 8.1–8.6) is implemented, tested, exposed through the
`/api/v1/search` and `/api/v1/questions` endpoints, and its knowledge base is
populated automatically — `DefaultProcessRelevantInformationUseCase` indexes an
item the moment relevance assessment confirms it. Phase 9's frontend covers
seven screens — Home, Sources, Interests, Signals (with Relevant Information
detail and feedback), Search, Q&A, and Activity, plus a Task 9 polish/integration pass;
Task 8 (Alerts frontend) is intentionally deferred — no alert backend
capability exists to build it against. **The next major implementation phase
is Phase 10 — Alerts**, beginning with the backend capability Task 8 found
missing. Of the Phase 9 soft dependencies (Section 12): **Q16** (default
ordering) and **Q18** (feedback change/free-text) are settled by what was
actually built — newest-first, and no change/free-text since the backend
offers neither. **Q15 remains genuinely open**: the signal list shows every
signal regardless of state (dismissed signals are not hidden), but no code
path — backend or frontend — ever transitions a signal into `REVIEWED`; it is
a defined `SignalState` value that nothing sets. This status section resolves
no open question and turns no provisional choice into a final decision.

---

## 3. Phase 0 — Documentation / Decision Baseline

**Status: Complete.** The documentation baseline (`docs/01`–`11`) exists and has
been reviewed together as one set (Phase 0 review, 2026-09-08); Phases 1–8 were
implemented from it with no blocking contradiction. The description below remains
accurate as an account of the review step that precedes implementation.

The project begins with the documentation baseline already produced:
`docs/01-product-spec.md` through `docs/11-roadmap.md` (this document) define
product scope, functional behavior, technical choices, architecture, data
model, AI capabilities, RAG, ingestion, evaluation, security, and this
roadmap.

Before implementation begins:

- review all documents together, as one coherent set;
- identify any contradiction between them (none is known at the time of
  writing, per the consistency checks performed while authoring each
  document);
- identify which decisions remain genuinely unresolved (Section 18 catalogs
  these);
- resolve **only** the decisions required for the next implementation step
  (Phase 1) — not every open question at once;
- record meaningful architectural decisions through ADRs where appropriate,
  as `CLAUDE.md` Section 22 already directs.

**Do not treat "all open questions resolved" as a precondition for starting
implementation.** Only decisions required for the current implementation
phase need to be resolved before that phase begins (Section 19).

---

## 4. Phase 1 — Project Foundation

**Status: Complete.** Every item below exists in the repository — the `backend/`,
`agents/`, and `frontend/` skeletons, `docker-compose.yml`, the Gradle version
catalog / `uv.lock` / frontend lockfile, `.github/workflows/`, health checks,
`/api/v1` conventions, and OpenAPI generation.

Uses only already-approved technology
(`docs/03-technical-spec.md` Section 4, 23): Java/Spring Boot, Python/FastAPI,
PostgreSQL/pgvector, React/TypeScript, Gradle, `uv`, Docker Compose, GitHub
Actions.

Foundation work establishes:

- repository structure;
- the Java backend project skeleton;
- the Python AI service project skeleton;
- the frontend project skeleton;
- a basic Docker Compose structure (`docs/03-technical-spec.md` Section 17.2);
- a local PostgreSQL instance;
- configuration conventions (`docs/03-technical-spec.md` Section 15);
- dependency/version management (Gradle version catalog, `uv.lock`, the
  frontend lockfile — `docs/03-technical-spec.md` Section 19.3);
- a CI baseline (`docs/03-technical-spec.md` Section 18.1);
- basic health checks;
- basic API conventions (`docs/03-technical-spec.md` Section 6.6, 12.1);
- OpenAPI generation (`docs/03-technical-spec.md` Section 4.13);
- the code-quality/security baseline already approved (Spotless, `ruff`,
  ESLint, Dependabot, CodeQL, Trivy — `docs/03-technical-spec.md`
  Section 18.1; `docs/10-security.md` Section 17).

No extra infrastructure is introduced. No exact package name is specified
beyond what `docs/04-architecture.md` Section 4 already fixes (domain,
application, interfaces, infrastructure).

---

## 5. Phase 2 — Database / Persistence Foundation

**Status: Complete.** Flyway migrations V1–V9 and the Spring Data JDBC
ports/adapters are implemented (`docs/adr/0001`, `docs/adr/0002`). The physical
identifier strategy that `docs/05-data-model.md` deliberately left open was
decided here as implementation work — `docs/adr/0001` records it (UUID primary
keys; a natural `TEXT` key for `area_of_interest`).

Built before the complete ingestion pipeline, using the data model already
defined in `docs/05-data-model.md`.

Establishes:

- the PostgreSQL schema, to the extent the data model already fixes it;
- a Flyway migration baseline (`docs/03-technical-spec.md` Section 4.8);
- Source persistence (`docs/05-data-model.md` Section 7);
- Raw Information Item persistence (`docs/05-data-model.md` Section 8);
- Relevant Information persistence (`docs/05-data-model.md` Section 9);
- Signal persistence (`docs/05-data-model.md` Section 10);
- provenance relationships (`docs/05-data-model.md` Section 15);
- Interest/Area-of-Interest configuration persistence
  (`docs/05-data-model.md` Section 5–6);
- Feedback persistence (`docs/05-data-model.md` Section 12);
- Activity Record persistence (`docs/05-data-model.md` Section 13);
- an embedding-storage foundation, where needed for later phases
  (`docs/05-data-model.md` Section 16).

**Final column definitions are not invented here where
`docs/05-data-model.md` leaves them open** — for example, the physical
identifier strategy (`docs/05-data-model.md` Section 17) and the exact
placement of embeddings (`docs/05-data-model.md` Section 16) remain open.
This roadmap describes **sequencing**, not schema design; those open
decisions are resolved as implementation work, at the point they are needed
(Section 18–19).

---

## 6. Phase 3 — Backend Core / Contracts

**Status: Complete.** The application use-case ports and implementations, the
`/api/v1` business API (RFC 9457 errors, OpenAPI), and the Java↔Python capability
contract and transport are implemented (`docs/adr/0003`, `docs/adr/0004`,
`docs/adr/0006`).

Establishes Java-owned application orchestration, conceptually:

- domain/application boundaries (`docs/04-architecture.md` Section 4);
- source management (add/edit/enable/disable/replace/remove —
  `docs/02-functional-spec.md` Section 4);
- processing state (`docs/05-data-model.md` Section 14);
- an idempotency foundation (`docs/05-data-model.md` Section 17;
  `docs/08-ingestion.md` Section 15);
- Java↔Python contracts (`docs/03-technical-spec.md` Section 8);
- typed API errors (`docs/03-technical-spec.md` Section 13.7, 12.1);
- the REST `/api/v1` surface (`docs/03-technical-spec.md` Section 6.6);
- basic source CRUD/configuration;
- orchestration boundaries (`docs/04-architecture.md` Section 7).

**The Java backend owns business state, persistence, transactions,
orchestration, and deterministic decisions** throughout this phase and every
phase after it. **Python does not own these responsibilities**
(`docs/04-architecture.md` Section 17).

AI capabilities are **not** implemented in this phase, except to the extent
needed to establish and validate the shape of a Java↔Python contract (e.g. a
minimal round-trip against one capability, to prove the envelope described in
`docs/03-technical-spec.md` Section 8.2 works end to end).

---

## 7. Phase 4 — Source Collection / Ingestion

**Status: Complete for its validation scope** (`docs/adr/0005`; DNS-rebinding
hardening in `docs/adr/0017`). One representative SSRF-guarded HTTP connector
behind the `SourceCollector` port, with deterministic normalisation, exact
deduplication, provenance, processing state, and idempotency. **Current
behavior:** `StartupIngestionRunner` runs the ingestion pipeline (collect →
group → process, over the sources configured and enabled at that moment) once, in
the background, when the backend process starts; each stage is idempotent, so a
restart does not duplicate work. There is **no recurring scheduler** and **no
collect-now endpoint or other manual trigger**. Recurring, scheduler-driven
collection (`docs/08-ingestion.md` Section 16) is a target design, not built —
its cadence is Q9 and a manual trigger is Q8 / T17, both still open — and the
final source list and source types remain open (Q1).

Implements the first complete, deterministic ingestion path, respecting
`docs/08-ingestion.md` throughout:

```text
Configured Source
      ↓
Collection
      ↓
Parsing / Extraction
      ↓
Normalization
      ↓
Exact Deduplication
      ↓
Persistence
```

Includes:

- configurable/replaceable source connectors (`docs/08-ingestion.md`
  Section 4);
- bounded collection (`docs/08-ingestion.md` Section 5;
  `docs/10-security.md` Section 5);
- SSRF protection (`docs/10-security.md` Section 6), including validated-address
  pinning so a DNS-rebinding hostname cannot bypass the check
  (`docs/adr/0017-outbound-fetch-ssrf-dns-rebinding.md`);
- safe parsing (`docs/08-ingestion.md` Section 6;
  `docs/10-security.md` Section 7);
- provenance (`docs/08-ingestion.md` Section 13);
- processing state (`docs/08-ingestion.md` Section 14);
- idempotency (`docs/08-ingestion.md` Section 15);
- retry/failure handling (`docs/08-ingestion.md` Section 17);
- explicit activity visibility (`docs/08-ingestion.md` Section 18).

**Not decided in this phase**, unless resolved elsewhere before it begins:
the final source list, the collection cadence, retry values, exact parser
libraries, or the exact source types (`docs/02-functional-spec.md` Q1, Q9,
Q10; `docs/03-technical-spec.md` Section 24, T9). These remain open questions
and gates (Section 18) — implementation of **one** representative connector
against **one** concrete source type is enough to validate this phase, even
before the full source list is settled.

---

## 8. Phase 5 — Semantic Deduplication / Relevant Information

**Status: Complete** (`docs/adr/0007`). The `near-duplicate` capability and the
Java-owned grouping into Relevant Information are implemented. The verdict is a
boolean "same underlying story?"; no numeric threshold is used or hardcoded
(Q12 / T15 remain open).

After deterministic ingestion works, semantic processing is added:

```text
Normalized Raw Information
          ↓
Near-Duplicate Assessment
          ↓
Relevant Information
```

Then classification and relevance assessment are integrated
(`docs/06-ai-agents.md` Section 4.1–4.3; `docs/08-ingestion.md` Section 9–10).

**Python performs the semantic AI capabilities** (near-duplicate similarity,
classification, relevance assessment). **Java remains responsible for
persistence and the deterministic decisions** that act on their output —
including the final near-duplicate threshold comparison and the decision to
retain an item as Relevant Information (`docs/06-ai-agents.md` Section 4.1,
item 6; Section 4.3, item 6).

**The near-duplicate threshold is not decided in this phase**
(`docs/02-functional-spec.md` Q12; `docs/03-technical-spec.md` Section 24,
T15). **No clustering infrastructure is introduced** — grouping remains
exactly the simple "attach to an existing Relevant Information record"
mechanism already approved (`docs/05-data-model.md` Section 18).

---

## 9. Phase 6 — Importance / Signal / Summary

**Status: Complete** (`docs/adr/0008`). The `relevance`, `importance`, and
`summarize` capabilities, deterministic Signal creation, and the source-grounded
Summary (migration V10) are implemented. The Java Signal transition is kept
deterministic and thin (Q4 / T16 remain open); summary length is provisional
(Q14 remains open). Classification is folded into the `relevance` capability —
see `docs/adr/0008` and `docs/06-ai-agents.md` Section 4.2.

Adds the next business-visible AI path:

```text
Relevant Information
        ↓
Importance Assessment
        ↓
Signal Decision
        ↓
Summary
        ↓
Persisted Signal
```

Keeps the distinction that runs through every prior document:

- **relevance ≠ importance** — two separate questions
  (`docs/06-ai-agents.md` Section 4.3–4.4);
- **AI assessment ≠ Java business-state decision** — the importance
  assessment is advisory; **the Java backend owns the final Signal state
  transition** (`docs/06-ai-agents.md` Section 6).

**Python provides the structured importance assessment and the summary**
(`docs/06-ai-agents.md` Section 4.4–4.5).

**Not decided or invented in this phase:**

- the signal-selection criteria — **Q4 / T16** remain open
  (`docs/02-functional-spec.md` Q4; `docs/03-technical-spec.md` Section 24,
  T16);
- summary length/form — **Q14** remains open
  (`docs/02-functional-spec.md` Q14);
- any recommendation/opportunity-scoring behavior — none exists in the
  approved product model and none is implemented here
  (`docs/01-product-spec.md` Section 9).

---

## 10. Phase 7 — Knowledge Base / Embeddings

**Status: Complete** (RAG Tasks 8.1–8.3B; `docs/adr/0009`–`0012`). See the note
below for how Tasks 8.1–8.6 map to this phase and Phase 8.

Adds the knowledge-base capabilities already defined in `docs/07-rag.md`:

```text
Approved Information
        ↓
Chunking
        ↓
Embedding Generation
        ↓
PostgreSQL / pgvector
```

Followed by support for semantic retrieval (Phase 8).

**Implementation — RAG Tasks 8.1–8.6.** These are implementation tasks *inside*
this roadmap's phases, not an alternative numbering. **Tasks 8.1–8.3B are this
phase (Phase 7):** the reusable RAG core, semantic chunking, the embedding
contract + a real local-model benchmark, and the PostgreSQL/pgvector
passage-index persistence with migration V11 (`docs/adr/0009`–`0012`).
**Tasks 8.3C–8.6 are Phase 8** (Section 11): the first real semantic retriever,
context assembler and grounded generator, and the independent deterministic
RAG-core evaluator (`docs/adr/0013`–`0016`). `docs/07-rag.md` Sections 20–27
record all six. `embeddinggemma` / 768 is the **provisional** embedding choice
(T3) and the provisional vector column; distance is cosine; no
ANN index yet (T7). Task 8.3C: `PgVectorRetriever` does `Query → EmbeddingModel
(TextRole.QUERY) → exact pgvector cosine Top-K`; `Query` gained a configurable
`topK` (provisional default 5); `score = 1 − cosineDistance`. End-to-end
retrieval benchmark over the 8.3A corpus: Recall@1 0.958, Recall@3–10 / MRR /
nDCG@10 = 1.000, ~123 ms/query. Task 8.4: `BudgetedContextAssembler` selects
whole passages in retrieval order until a model-independent **character** budget
(provisional default 12 000) is reached, removes exact-duplicate passage ids,
preserves provenance and never modifies passage text; `Context` stays a
structured object. Task 8.5: `AiCapabilityAnswerGenerator` calls a new `answer`
Python capability (contract v1) through the Task 6A invoker — provider-independent,
structured JSON output, citations of passage ids only (Java attaches provenance
from the context). Grounding is validated structurally in three layers (Python
`extra_check` + one bounded repair, Java re-check, RAG-core
`GroundingAnswerValidator`); an unsupported "answered" verdict is downgraded to
insufficient evidence; retrieved text is untrusted; a provider/timeout/repair
failure is a typed `GenerationException`. The query-time `RagPipeline` bean now
runs retriever → assembler → generator → grounding validator. Task 8.6:
`RagExecutionEvaluator` is a deterministic `Evaluator` **outside** the runtime
pipeline — it consumes one immutable `RagExecution` + a hand-authored versioned
`RagEvaluationDataset` and returns named `EvaluationMetric`s (retrieval
`recall@1/3/5/10` · `reciprocalRank` · `ndcg@10`, `answerabilityAgreement`,
structural `citationValidity`) plus findings; `RagEvaluationSummary` means a
metric across a run set. **No LLM judge, no semantic factuality scoring, no
combined RAG score** — those stay open decisions. Hybrid retrieval, reranking,
query transformation, business metadata filtering, context compression /
`ContextRefiner`, and any LLM-as-a-judge all stay deferred. As of Tasks 8.1–8.3B
nothing yet indexed content automatically; index population and the search and
question APIs were wired afterwards, in Phase 8 (Section 11).

**The exact embedding model, vector dimension, chunking strategy, vector
index type, and related parameters remain open unless explicitly decided
before this phase begins.** Preserved:

- **T3** — embedding model/vector dimension (`docs/03-technical-spec.md`
  Section 24, T3);
- **T7** — pgvector index strategy (`docs/03-technical-spec.md` Section 24,
  T7);
- **Q12 / T15** — near-duplicate criteria/threshold, where relevant to shared
  embeddings between near-duplicate assessment and retrieval
  (`docs/07-rag.md` Section 13).

**Not introduced:** a separate vector database, Elasticsearch/OpenSearch,
reranking, hybrid search, a knowledge graph, or agentic retrieval — none of
these are approved (`docs/07-rag.md` Section 16).

---

## 11. Phase 8 — Semantic Search / Grounded Q&A

**Status: Complete** (RAG Tasks 8.3C–8.6; `docs/adr/0013`–`0016`).
The retrieval → context assembly → grounded generation → grounding validation →
deterministic evaluation chain is implemented, composed as a `RagPipeline` bean,
and covered by unit and integration tests. It is exposed through the
`/api/v1/search` and `/api/v1/questions` endpoints, each a thin REST adapter
over an application use case (`SemanticSearchUseCase`, `AskQuestionUseCase`)
that delegates to the existing `Retriever` / `RagPipeline` ports — no retrieval,
context assembly, generation, or grounding logic was reimplemented at the API
boundary. The pgvector index is populated automatically: once relevance
assessment confirms an item (`DefaultProcessRelevantInformationUseCase`), the
new `KnowledgeBaseIndexer` port runs it through the existing `IndexingPipeline`
outside the relevance database transaction — no scheduler, no background
worker, and no backfill for records processed before this wiring existed.
`embeddinggemma` / 768 (T3) and the exact pgvector cosine scan with no ANN
index (T7) remain the provisional baseline; the advanced RAG techniques below
remain deferred.

Implements the two approved RAG capabilities (`docs/07-rag.md`).

**Semantic search** — Java prepares the query, requests its embedding,
retrieves via pgvector, applies basic metadata filtering, and returns results
with their source/provenance information (`docs/07-rag.md` Section 7, 9).

**Grounded Q&A:**

```text
User Question
      ↓
Query Embedding
      ↓
PostgreSQL / pgvector Retrieval
      ↓
Retrieved Passages
      ↓
Python Grounded Synthesis
      ↓
Validated Answer
      ↓
Sources / Citations
```

The answer must remain grounded strictly in the supplied passages;
insufficient evidence must be reported explicitly, never fabricated
(`docs/06-ai-agents.md` Section 4.7; `docs/07-rag.md` Section 10, 14).

**Not introduced in this phase:**

- multi-turn Q&A, unless **Q21** is explicitly decided before this phase
  begins (`docs/02-functional-spec.md` Q21);
- any advanced RAG technique beyond what `docs/07-rag.md` Section 16
  approves.

---

## 12. Phase 9 — Frontend

**Status: Complete for its scope (Tasks 1–7 and Task 9; Task 8 Alerts
deferred to Phase 10).** The Phase 1 skeleton (`frontend/`) now has a typed API
client generated from the backend's OpenAPI document (`frontend/src/api/`;
`npm run generate:api`) and the following seven product screens: **Home** (`frontend/src/home/`) — the
default screen, a newest-first view of recent relevant information read from
`GET /api/v1/relevant-information`, with a **Signal** badge and the same signal
detail view when a record has become a Signal; **Sources**
(`frontend/src/sources/`) and **Interests** within the six fixed areas
(`frontend/src/interests/`) — list, create, edit, and enable/disable, using
`GET/POST/PUT /api/v1/sources`, `GET/POST/PUT /api/v1/interests`, and the
read-only `GET /api/v1/areas-of-interest`. Neither resource can be removed
through the UI: the backend has no delete operation for a source or an
interest, a documented open decision (Q2/Q3 for sources, the analogous open
question for interests — see `ManageSourcesUseCase`/`ManageInterestsUseCase`),
not an oversight of this task. **Signals** (`frontend/src/signals/`) — a
newest-first list via `GET /api/v1/signals` (a Task-3 backend-enabling
addition, since no list endpoint previously existed), with a Relevant
Information / provenance drill-down. **Search** (`frontend/src/search/`) and
**Q&A** (`frontend/src/qa/`) over the existing `POST /api/v1/search` and
`POST /api/v1/questions`. **Activity** (`frontend/src/activity/`) — a
read-only, newest-first feed via `GET /api/v1/activity`. **Feedback** is
integrated into the Signals detail screen rather than a standalone screen
(`docs/02-functional-spec.md` Section 10.2: "Feedback is visible in the
signal's detail view"), using the existing
`POST /api/v1/signals/{signalId}/feedback`: the only two options offered are
the backend's `FeedbackVerdict` values (`RELEVANT` / `NOT_RELEVANT`); the
control is shown only while `signal.state` is `NEW`/`REVIEWED` and is replaced
by a plain acknowledgment once feedback has moved the signal to `KEPT`/
`DISMISSED` — the only code path that sets those two states, and the
frontend's only way to detect "feedback already recorded" absent a dedicated
read endpoint. The frontend simply reflects the backend's existing feedback
contract as built: no change/withdraw endpoint exists, and no free-text field
is accepted (only the two-value `FeedbackVerdict` enum) — this task does not
choose a provisional default for **Q18**, it implements what the backend
already does. **Task 8 (Alerts frontend) was attempted and found blocked**: no
alert domain object, migration, repository, use case, controller, or OpenAPI
schema exists anywhere in the backend — a total gap, not a partial one — so no
Alerts UI was built; it is deferred to Phase 10 (Section 13), which must
build the backend capability first. **Task 9 (polish/integration)** reviewed
navigation, loading/error/empty states, data safety, API usage, and
accessibility across the six screens that existed at the time (Home was added afterwards): it added the Retry action that was
missing from the Sources and Interests load-error states (present on every
other screen) and extracted the identical provenance-rendering markup
duplicated between Search and Q&A into one shared `ProvenanceDetails`
component; no other concrete defect was found. Phase 8 is
complete: the endpoints the UI consumes already exist, and the knowledge base
they read from is populated automatically as relevant information is
confirmed — no remaining backend dependency blocks this phase.

Builds the React frontend around the now-stable backend API
(`docs/04-architecture.md` Section 10).

The MVP UI supports the already-approved product capabilities
(`docs/02-functional-spec.md` Sections 4–13):

- source configuration;
- interest configuration;
- signal review;
- signal details;
- original source links;
- summaries;
- relevance feedback;
- semantic search;
- grounded Q&A;
- basic activity/failure visibility;
- simple alerts configuration/status, once Phase 10 exists.

**No additional UI feature is invented.** **No frontend state-management
library is introduced** — React state and the generated API client remain
sufficient (`docs/03-technical-spec.md` Section 4.14). **The frontend has no
direct database access** at any point (`docs/04-architecture.md` Section 10,
17 item 5).

**Soft dependencies for this phase (not hard gates):** the signal-review and
feedback screens depend on answers to **Q15** (is "Reviewed" tracked
automatically; are dismissed signals hidden or kept), **Q16** (default
signal-list ordering), and **Q18** (can feedback be changed/withdrawn; are
free-text comments supported). Unlike Q19 for Phase 10, these do not block
Phase 9 from starting — a reasonable provisional behavior can be implemented
and revised later without an architecture change. Each must, however, be
**either resolved before Phase 9 is considered complete, or implemented
against an explicitly documented provisional default.** Of the three: **Q16**
is settled by what was built — the signal list is newest-first, with no
frontend re-sorting. **Q18** is answered by the backend as already built — no
change/withdraw endpoint, no free-text field — which the Feedback frontend
task simply implements. **Q15 remains genuinely open** and is not resolved by
this roadmap or by the implementation: dismissed and kept signals are *not*
hidden from the list (they remain visible with their state shown, which is the
implemented behavior, not a chosen default), but "is 'Reviewed' tracked
automatically" is unaddressed — no code path, backend or frontend, ever
transitions a signal into `REVIEWED`; it is a `SignalState` value the domain
defines but nothing sets. This is a pre-existing backend gap the Task 9
integration review surfaced, not something to silently decide or work around
here.

---

## 13. Phase 10 — Alerts

**Status: Not started.** Begins only once Q19 (alert channel) and Q20 (alert
retry) are decided; these remain gates and are not decided here. Phase 9 Task
8 (Alerts frontend) inspected the backend looking for something to build
against and found nothing at any layer: no `Alert` domain object, no
migration, no repository, no use case, no controller, and no `alerts` path in
the OpenAPI document — only a doc-comment in `MetaController` naming
`alert-settings` as a future business endpoint. This is a total gap, and it
sits behind the Q19/Q20 gates below, so this phase's backend work is where
Alerts genuinely begins.

Implemented only after Signal generation (Phase 6) is stable.

Respects:

- **Q19** — the alert channel (`docs/02-functional-spec.md` Q19);
- **Q20** — the alert retry policy (`docs/02-functional-spec.md` Q20).

**This roadmap does not select the channel.** The implementation of this
phase begins only once that decision has been made explicitly — it is a gate
(Section 18), not something resolved by sequencing.

**Not introduced:** per-interest alert rules, sophisticated notification
preferences, or a notification orchestration platform — none of these are
approved (`docs/02-functional-spec.md` Section 11.2, R6).

---

## 14. Phase 11 — Evaluation / Hardening

**Status: Not started.** This is the dedicated, systematic evaluation and
security-hardening pass over the end-to-end MVP. The deterministic RAG-core
`Evaluator` implemented in Task 8.6 (`docs/adr/0016`) is a contract-level
component consuming a single `RagExecution`; it is **not** this pass and **not**
the dedicated AI evaluation subsystem, which remains deferred (Section 16).

**Ordinary testing is not deferred to this phase.** Unit, integration, and
contract tests (`docs/03-technical-spec.md` Section 16) run **continuously
from Phase 1 onward**, inside each phase's own CI gate
(`docs/03-technical-spec.md` Section 18.1, required to merge) — each phase is
expected to land with the tests for the behavior it adds. Phase 11 is the
**dedicated, systematic evaluation and security-hardening pass** performed
once the end-to-end MVP flow exists, not the point at which testing begins.

Once the core MVP flow exists end to end (Section 15), that systematic quality
and security hardening follows, using `docs/09-evaluation.md` and
`docs/10-security.md` directly — neither document's content is redefined
here.

**Evaluation** covers: deterministic correctness, provenance, idempotency, AI
output validation, relevance, importance, summaries, near-duplicates,
retrieval, grounded Q&A, and failure handling
(`docs/09-evaluation.md` Section 3–11).

**Security hardening** covers: SSRF, malicious/malformed content, prompt
injection, API input validation, secret handling, database safety,
dependency scanning, and container security
(`docs/10-security.md` Section 5–18).

**No dedicated evaluation platform and no dedicated security platform are
created** — both remain explicitly out of MVP scope
(`docs/09-evaluation.md` Section 18; `docs/10-security.md` Section 23).

---

## 15. MVP Completion Criteria

The MVP is complete when the following end-to-end flow works:

```text
Reliable Configured Source
          ↓
       Collect
          ↓
      Normalize
          ↓
       Deduplicate
          ↓
      Relevance
          ↓
      Importance
          ↓
        Signal
          ↓
       Summary
          ↓
   Review + Source Link
          ↓
 Search / Grounded Q&A
          ↓
      Simple Alert
```

(`docs/01-product-spec.md` core MVP flow; `docs/02-functional-spec.md`
Section 3; `docs/08-ingestion.md` Section 3.)

The MVP also provides:

- configurable sources (`docs/02-functional-spec.md` Section 4);
- provenance (`docs/05-data-model.md` Section 15);
- feedback (`docs/02-functional-spec.md` Section 10);
- basic failure visibility (`docs/02-functional-spec.md` Section 15;
  `docs/05-data-model.md` Section 13);
- basic security controls (`docs/10-security.md` Section 23);
- basic evaluation/testing (`docs/09-evaluation.md` Section 18;
  `docs/03-technical-spec.md` Section 16);
- a local, reproducible deployment (`docs/03-technical-spec.md` Section 17).

No capability beyond those already approved in `docs/01-10` is added to this
definition of "complete."

---

## 16. Explicitly Deferred Work

The following must **not** be implemented during the MVP. Every item is
already deferred by `docs/01-10` — none is newly invented here.

- Authentication (`docs/02-functional-spec.md` R14).
- Multi-user support (`docs/01-product-spec.md` Section 9).
- External actions beyond notifying the user
  (`docs/02-functional-spec.md` Section 9.5).
- Open-ended Internet search (`docs/01-product-spec.md` Section 8).
- Additional areas of interest beyond the six approved
  (`docs/02-functional-spec.md` R20).
- A signal taxonomy (`docs/02-functional-spec.md` R21).
- Source-authority scoring (`docs/02-functional-spec.md` R22).
- Complex personalization (`docs/01-product-spec.md` Section 9).
- Forecasting/prediction (`docs/01-product-spec.md` Section 9).
- Trend algorithms (`docs/01-product-spec.md` Section 9).
- Correlation engines (`docs/01-product-spec.md` Section 9).
- Opportunity scoring (`docs/01-product-spec.md` Section 9).
- Sophisticated ranking (`docs/07-rag.md` Section 16).
- Optional reranking (`docs/03-technical-spec.md` Section 25).
- Feedback-driven model training (`docs/02-functional-spec.md` R8).
- Multilingual processing/translation (`docs/02-functional-spec.md`
  Section 15.3).
- A dedicated AI evaluation subsystem (`docs/09-evaluation.md` Section 18) —
  still deferred; the deterministic RAG-core `Evaluator` from Task 8.6
  (`docs/adr/0016`) is a contract-level component consuming a single
  `RagExecution`, not this subsystem/platform.
- An observability dashboard (`docs/03-technical-spec.md` Section 14.5).
- A public API (`docs/03-technical-spec.md` Section 25).
- Distributed/microservice complexity (`docs/03-technical-spec.md`
  Section 25).
- A separate vector database (`docs/07-rag.md` Section 16).
- Advanced RAG techniques (`docs/07-rag.md` Section 16).
- Agentic workflows (`docs/06-ai-agents.md` Section 2, item 8).
- Kubernetes (`docs/03-technical-spec.md` Section 25).
- An API gateway (`docs/10-security.md` Section 23).
- A WAF (`docs/10-security.md` Section 23).
- A SIEM (`docs/10-security.md` Section 23).
- Enterprise secrets management (`docs/10-security.md` Section 23).
- A/B testing (`docs/09-evaluation.md` Section 15, 18).
- A model leaderboard (`docs/09-evaluation.md` Section 18).
- A large benchmark platform (`docs/09-evaluation.md` Section 18).

No additional deferred feature is invented beyond this list.

---

## 17. Dependency Map

```text
Foundation
    ↓
Persistence
    ↓
Backend Contracts
    ↓
Ingestion
    ↓
Deterministic Dedup
    ↓
AI Processing
    ↓
Signal
    ↓
Embeddings
    ↓
Search / Q&A
    ↓
Frontend
    ↓
Alerts
    ↓
Evaluation / Security Hardening
```

Key dependencies:

- **AI processing depends on normalized, persisted information** — there is
  nothing to classify or assess relevance for until Phase 4 has produced it.
- **Signal creation depends on relevance and importance decisions** — a
  Signal cannot exist without the Relevant Information and the importance
  assessment that precede it (`docs/06-ai-agents.md` Section 4.3–4.4).
- **RAG depends on persisted information plus embeddings** — search and Q&A
  need both a knowledge base (Phases 4–6) and the vectors that make it
  retrievable (Phase 7).
- **Q&A depends on retrieval** — Grounded Q&A never retrieves on its own
  (`docs/07-rag.md` Section 10); it needs Java-side retrieval (Phase 8, first
  half) to exist first.
- **The frontend depends on stable backend contracts** — building UI against
  an API that is still changing shape wastes effort; the API from Phase 3
  onward should be reasonably settled before Phase 9 begins in earnest.
- **Alerts depend on stable Signal creation** — an alert is raised only for a
  new Signal (`docs/02-functional-spec.md` Section 11.2), so Phase 10 needs
  Phase 6 to already work.

**This does not imply every item must be 100% complete before the next
starts.** The architecture supports incremental, vertical implementation — for
example, a thin end-to-end path (one source type, one area, no search yet)
can validate Phases 4–6 together before every source type or every AI
capability edge case is handled.

---

## 18. Open Questions / Gates

Existing open questions, mapped to the phase where they must be resolved —
**before that phase**, not before the project starts.

**Status note (2026-09-08).** Phases 1–8 have been implemented (Section 2.1).
Where a completed phase genuinely required a gate decision it was made as
implementation work per Section 19 — the **physical identifier strategy** for
Phase 2 was decided in `docs/adr/0001` (UUID primary keys; a natural `TEXT` key
for `area_of_interest`). Every other gate below **remains open**: the completed
phases used the documented provisional direction (a boolean near-duplicate
verdict rather than a threshold; a thin, deterministic Signal transition;
`embeddinggemma` / 768 as a provisional embedding; an exact pgvector cosine scan
as the retrieval baseline) and did not close the question.

**Before source implementation (Phase 4):**
- **Q1** — source types (`docs/02-functional-spec.md` Q1)
- **Q2** — reachability / "same source" definition
  (`docs/02-functional-spec.md` Q2)
- **Q9** — collection cadence (`docs/02-functional-spec.md` Q9)
- **Q10** — retry/backoff (`docs/02-functional-spec.md` Q10)
- **T9** — timeout/retry values (`docs/03-technical-spec.md` Section 24, T9)

**Before semantic deduplication (Phase 5):**
- **Q12 / T15** — near-duplicate criteria/threshold
  (`docs/02-functional-spec.md` Q12; `docs/03-technical-spec.md` Section 24,
  T15)

**Before signal implementation (Phase 6):**
- **Q4 / T16** — signal-selection criteria/guardrails
  (`docs/02-functional-spec.md` Q4; `docs/03-technical-spec.md` Section 24,
  T16)

**Before summary implementation (Phase 6):**
- **Q14** — summary form/length (`docs/02-functional-spec.md` Q14)

**Before RAG implementation (Phase 7):**
- **T3** — embedding model/dimension (`docs/03-technical-spec.md`
  Section 24, T3)
- **T7** — pgvector index (`docs/03-technical-spec.md` Section 24, T7)
- chunking-related unresolved decisions (`docs/07-rag.md` Section 4, 18)

**Before multi-turn Q&A (Phase 8, optional extension):**
- **Q21** (`docs/02-functional-spec.md` Q21)

**Before multilingual support (post-MVP):**
- **Q24 / T19** (`docs/02-functional-spec.md` Q24;
  `docs/03-technical-spec.md` Section 24, T19)

**Before alerts (Phase 10):**
- **Q19** — alert channel (`docs/02-functional-spec.md` Q19)
- **Q20** — alert retry (`docs/02-functional-spec.md` Q20)

**Before retention implementation:**
- **Q22 / T18** (`docs/02-functional-spec.md` Q22;
  `docs/03-technical-spec.md` Section 24, T18)

**Before manual retry/reprocessing:**
- **Q25 / T17** (`docs/02-functional-spec.md` Q25;
  `docs/03-technical-spec.md` Section 24, T17)

**These are implementation gates, not decisions.** This roadmap does not
resolve any of them; it only states which phase cannot begin until each is
settled.

**Soft dependencies — before Phase 9 (Frontend) is considered complete, not
before it starts:**
- **Q15** — signal lifecycle: is "Reviewed" tracked automatically; are
  dismissed signals hidden or kept (`docs/02-functional-spec.md` Q15)
- **Q16** — default signal-list ordering (`docs/02-functional-spec.md` Q16)
- **Q18** — can feedback be changed/withdrawn; are free-text comments
  supported (`docs/02-functional-spec.md` Q18)

These shape the signal-review and feedback screens but do not block Phase 9
from starting — a reasonable provisional behavior can be implemented and
revised later without an architecture change. Each must be **either resolved,
or implemented against an explicitly documented provisional default**, before
Phase 9 is treated as done. This roadmap resolves none of them and chooses no
default.

---

## 19. ADR / Decision Workflow

How an unresolved decision should be handled during implementation:

```text
Open Question
     ↓
Determine whether the current phase requires it
     ↓
   ┌───┴───┐
  yes      no
   ↓        ↓
Evaluate   Leave it open;
alternatives  do not build around
   ↓        an invented assumption
Make an explicit decision
   ↓
Document the decision / ADR where appropriate
   ↓
Implement
```

If a decision is **not** required by the current phase: leave it open, do not
prematurely decide it, and do not build around an invented assumption if it
can be avoided. This preserves changeability
(`CLAUDE.md` Section 29) and is the same discipline every prior document in
this series has followed.

---

## 20. Implementation Checkpoints

Lightweight checkpoints between phases — no numeric performance target is
defined at any checkpoint.

**Status (2026-09-17):** Checkpoints 1–7 are met. Checkpoint 6 (RAG) is met —
semantic search and grounded Q&A run as a tested `RagPipeline`, exposed through
the `/api/v1/search` and `/api/v1/questions` endpoints, against a knowledge base
populated automatically from stored Signal Engine content. Checkpoint 7
(Product) is met by Phase 9's complete frontend. Checkpoint 8 (Hardening) is
upcoming.

- **Checkpoint 1 — Foundation.** The project builds and starts locally
  (Phase 1).
- **Checkpoint 2 — Persistence.** Schema/migrations work and core persistence
  is testable (Phase 2).
- **Checkpoint 3 — Ingestion.** A configured source can be collected,
  normalized, deduplicated, and persisted (Phase 4).
- **Checkpoint 4 — AI processing.** A stored item can pass through the
  approved AI capabilities with validated contracts (Phases 5–6).
- **Checkpoint 5 — Signal.** A complete source item can become a persisted
  Signal through the approved flow (Phase 6).
- **Checkpoint 6 — RAG.** Semantic search and grounded Q&A work against
  stored information (Phases 7–8).
- **Checkpoint 7 — Product.** The frontend exposes the approved MVP
  capabilities (Phase 9).
- **Checkpoint 8 — Hardening.** Evaluation and security checks cover the
  agreed MVP boundaries (Phase 11).

---

## 21. Implementation Order — Practical Version

A concise, numbered order suitable as an actual execution sequence:

1. Review and validate `docs/01-11`.
2. Resolve only the decisions required for the foundation (Phase 1).
3. Create the project skeleton (backend, AI service, frontend, Compose).
4. Establish PostgreSQL/Flyway.
5. Establish the Java domain/application/persistence boundaries.
6. Establish the Java↔Python contracts.
7. Implement source management (configuration CRUD).
8. Implement one end-to-end source connector.
9. Implement normalization and exact deduplication.
10. Add the semantic AI capabilities (near-duplicate, classification).
11. Add relevance and importance assessment.
12. Create Signals and summaries.
13. Add embeddings/pgvector.
14. Add semantic search.
15. Add grounded Q&A.
16. Build the frontend.
17. Add alerts, after the Q19/Q20 decisions are made.
18. Perform evaluation/security hardening.
19. Validate the complete MVP flow (Section 15).

No source-code-level implementation detail is defined here — this is a
sequence, not a design.

**Current position (2026-09-17):** steps 1–16 are done; semantic search and
grounded Q&A are implemented as a `RagPipeline`, exposed through
`/api/v1/search` and `/api/v1/questions`, the knowledge base they read from is
populated automatically as relevant information is confirmed, and the frontend
(Phase 9) covers Sources, Interests, Signals with feedback, Search, Q&A, and
Activity. The next major step is **17 — add alerts**, once the Q19/Q20
decisions are made. Steps 18–19 follow.

---

## 22. Post-MVP Direction

Post-MVP evolution should be driven by **actual observed needs and evaluation
results**, not by adding complexity preemptively
(`docs/03-technical-spec.md` Section 3.8; `CLAUDE.md` Section 19).

Potential future directions, all already identified as deferred elsewhere in
`docs/01-10` (Section 16 above), may include: multilingual behavior,
multi-user/authentication, richer evaluation, stronger observability,
additional source types, more advanced retrieval, and additional
infrastructure. **None of these is prioritized or committed to here** — this
roadmap only confirms they remain possible future directions already named
elsewhere, not a plan for when or whether they happen.

---

## 23. Summary

**Build the smallest complete vertical Signal Engine path first, validate it,
then expand capability by capability without breaking the established
boundaries.**

The documentation phase is now complete: `docs/01-product-spec.md` through
this roadmap define what Signal Engine is, how it behaves, how it is built,
how its data and AI capabilities are structured, how retrieval and ingestion
work, how quality and security are approached, and now, how implementation
should be sequenced. Implementation should proceed **incrementally**, phase by
phase, with unresolved decisions handled **just before** the phase that
actually needs them — never earlier, and never left unresolved past the point
where a phase genuinely requires an answer. The MVP remains intentionally
simple; the architecture remains replaceable and changeable at every
boundary already established; and no premature complexity — technical,
product, or organizational — is introduced by this roadmap or by the
implementation it describes.

As of 2026-09-17, Phases 0–9 are complete for their scope: Phase 8's RAG
pipeline (Tasks 8.1–8.6) is implemented, tested, exposed through `/api/v1`,
and its knowledge base is populated automatically; Phase 9's frontend covers
seven screens (Home, Sources, Interests, Signals with feedback, Search, Q&A,
and Activity; Section 2.1). The next major implementation phase is **Phase 10 — Alerts**,
gated on Q19 and Q20. Every `Q*` / `T*` decision that was open before this
update is still open, except the physical identifier strategy, decided in
`docs/adr/0001`.
