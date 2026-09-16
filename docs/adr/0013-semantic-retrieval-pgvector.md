# ADR 0013 — Semantic Retrieval with PostgreSQL + pgvector

Status: Accepted
Date: 2026-09-07
Phase: 2 (docs/11-roadmap.md), Task 8.3C — the first real `Retriever`

---

## Context

Task 8.1 fixed the RAG contracts, 8.2 added chunking, 8.3A added the embedding
contract plus a real-benchmark provisional model, and 8.3B persisted chunked,
embedded passages to PostgreSQL + pgvector (`rag_passage`,
`rag_passage_embedding`, migration V11). Task 8.3C closes the read side:

```
Query → EmbeddingModel (TextRole.QUERY) → query vector
      → PostgreSQL + pgvector, exact cosine scan (<=>)
      → Top-K RetrievedPassages → RetrievalResult
```

Constraints (from the task and `CLAUDE.md`): the generic RAG core
(`org.signalengine.rag.**`) stays framework-, persistence- and business-free
(no Spring, JDBC, PostgreSQL, pgvector, Jackson, Signal Engine domain types);
Java owns DB interaction; Python owns embedding generation (the 8.3A `embed`
capability is reused unchanged); no HNSW/IVFFlat, no hybrid or keyword retrieval,
no reranking, no query rewriting/expansion, no generation, no evaluation
subsystem, no Signal Engine business ranking. **Semantic retrieval only.**

## Decisions

### 1. Reuse the generic `Retriever` contract; no new retrieval abstraction

`Retriever` (`RetrievalResult retrieve(Query)`), `RetrievedPassage` and
`RetrievalResult` are used verbatim from Task 8.1. The concrete implementation is
an infrastructure adapter, `PgVectorRetriever`. The generic contract exposes no
PostgreSQL, pgvector or JDBC type and is reusable in another project with a
different store — verified by `RagCoreBoundaryTest` (imports) and an integration
test (contract return types via reflection).

### 2. Query embedding uses the existing generic `EmbeddingModel`

Step 2 of the flow embeds `Query.text()` through the **existing** generic
`EmbeddingModel` with `TextRole.QUERY`. No `QueryEmbeddingModel`, no
`OllamaQueryEmbeddingModel`, no EmbeddingGemma- or provider-specific query type
was created. The RAG core already models the query/passage asymmetry via
`TextRole`; the concrete `AiCapabilityEmbeddingModel` → `embed` Python capability
→ Ollama applies whatever prefix the model documents. There is exactly one
embedding architecture.

### 3. `Query` gains one first-class field: `int topK`

`Query` was the minimum generic addition needed for retrieval configuration. It
now has a canonical `topK` alongside the query text and metadata filters:

- `DEFAULT_TOP_K = 5` — provisional (retrieval parameters are open, `07-rag.md`
  §7/§18); `Query.of(text)` keeps working and defaults to it.
- `MAX_TOP_K = 200` — a hard ceiling; a query can never request an unbounded
  scan. The SQL always has a `LIMIT`.
- `topK` outside `[1, MAX_TOP_K]` is rejected by the canonical constructor.

No ranking formulas, user-preference scores, business importance, Signal Engine
categories or query planning were added.

### 4. `PgVectorRetriever` — exact cosine scan over the 8.3B schema

`PgVectorRetriever` (infrastructure, package-private, `@Repository`, auto-wired
`EmbeddingModel` + `JdbcClient`, matching the 8.3B `PgVectorIndexedPassageStore`
pattern — no separate `@Bean` config). One SQL statement joins
`rag_passage_embedding` to `rag_passage` (**no new table, no second store, no
duplicate passage storage**), computes `embedding <=> :queryVector` as
`cosine_distance`, filters by embedding-model identity (Decision 6),
`ORDER BY cosine_distance ASC, p.id ASC LIMIT :topK`, and maps each row to a
generic `RetrievedPassage` preserving provenance and passage metadata.

### 5. Distance metric: pgvector cosine `<=>`; score = `1 − distance`

The DB operation is explicit: the cosine **distance** operator `<=>` (0 =
identical direction, 2 = opposite), ordered ascending (nearest first), consistent
with the 8.3A benchmark and the 8.3B schema decision. Where the public RAG API
needs a *similarity*, the conversion is explicit and documented:
`RetrievedPassage.score() = 1.0 − cosineDistance` (higher = more similar). The raw
distance is kept verbatim in the passage metadata (`cosineDistance`) and the
convention is recorded in `RetrievalResult.metadata()` (`scoreConvention`). No
proprietary score, no similarity threshold, no dropping of "low-looking" matches
— Top-K returns the best available candidates.

### 6. Embedding-model compatibility is enforced in the query

The provisional model is `embeddinggemma` / 768 (T3) — **not** hardcoded in the
generic core. `PgVectorRetriever` compares the query vector only against stored
embeddings whose persisted identity — `embedding_provider`, `embedding_model`,
`embedding_model_version`, `embedding_dimension` — equals the descriptor returned
when the query was embedded. Vectors from a different embedding model are never
mixed in; if a passage carries several models' vectors (8.3B allows this), only
the matching one is used; if no compatible embedding exists the result is empty
(not an error). Model replacement needs no change to the `Retriever` abstraction:
re-index under the new identity and queries follow.

### 7. Metadata filtering: minimal and generic only; business filtering deferred

`PgVectorRetriever` honours two **generic provenance-identity** keys from
`Query.metadataFilters()` — `contentId` and `sourceId` — as exact-match column
constraints. Any other key (`area`, `interest`, time window…) is a Signal Engine
**business** concept that cannot live in the generic core; it is recorded under
`RetrievalResult.metadata()` `ignoredFilters` rather than applied. Area / interest
/ time filtering is **deferred** — it needs a schema change or a filter DSL,
neither of which this task introduces. Semantic retrieval is the goal.

### 8. No ANN index (T7 stays open)

No HNSW or IVFFlat index is added. The corpus is small, retrieval is not
benchmarked at scale, the index type and parameters **are** open question T7, and
an **exact** cosine scan is a clean, reproducible reference baseline for a later
exact→ANN comparison. An integration test asserts no `hnsw`/`ivfflat` index
exists on `rag_passage_embedding` and retrieval still works.

### 9. A real end-to-end retrieval benchmark

`PgVectorRetrievalBenchmark` (`@Tag("benchmark")`, `./gradlew retrievalBenchmark`)
reuses the **8.3A corpus and human-authored relevance judgments verbatim** (36
passages, 24 queries, 6 areas); no ground truth is invented, altered or tuned per
query. It embeds the passages with real `embeddinggemma`, indexes them through the
real `PgVectorIndexedPassageStore` into a real PostgreSQL + pgvector, and answers
every query through the real `PgVectorRetriever` — validating the **complete
path**, not the embedding model alone. Metrics mirror the 8.3A `metrics.py`
(Recall@1/3/5/10, MRR, nDCG@10).

Run of 2026-09-07 (`embeddinggemma` / 768, exact cosine, `topK = 10`): Recall@1
**0.958**, Recall@3/5/10 **1.000**, MRR **1.000**, nDCG@10 **1.000**; avg query
latency **123 ms** (min 105 / p50 121 / p95 145 / max 146); 0 failures, every
query's first relevant passage at rank 1. This **matches** the 8.3A
embedding-only baseline within float32 rounding — the expected result, and the
point: it confirms the Java DB interaction, the score conversion, provenance /
metadata carry-through and determinism. It is **not** evidence that
`embeddinggemma` is permanently selected, that HNSW is selected, or that
retrieval is production-optimised.

## Consequences

- RAG core: `rag.query.Query` gains `topK` + `DEFAULT_TOP_K` / `MAX_TOP_K` and a
  `withTopK` helper (backward compatible). Nothing else in the core changes;
  `Retriever` / `RetrievedPassage` / `RetrievalResult` are untouched.
- Infrastructure: `PgVectorRetriever` + `package-info.java` in
  `org.signalengine.infrastructure.rag.retrieval`. No new Spring config class
  (`@Repository` auto-discovery, like 8.3B).
- Tests: `QueryTest`; `RetrievalMetrics` + `RetrievalMetricsTest` (Java
  counterpart of `metrics.py`); `PgVectorRetrieverIntegrationTest` (9 tests, real
  PostgreSQL, crafted 768-d vectors, all Step-11 verification points);
  `PgVectorRetrievalBenchmark` (`benchmark` tag). New Gradle task
  `retrievalBenchmark`; `tasks.test` now also `excludeTags("benchmark")`.
- No migration. No new dependency (`JdbcClient`, Jackson 3, Testcontainers already
  present).
- No search API, no use case, no scheduler — nothing calls `PgVectorRetriever`
  in production yet; the integration test and benchmark exercise it.
- `docs/07-rag.md` gains Section 24 (Summary → 25); `docs/03-technical-spec.md`
  Sections 11.2 / 11.3 and Section 24 (T3, T7) and `docs/11-roadmap.md` Phase 2
  progress updated.

## Trade-offs

- **Exact scan, not ANN.** Deliberate (Decision 8): correct and fully accurate at
  MVP scale, and the reproducible baseline the eventual T7 tuning is measured
  against. Premature HNSW tuning is exactly what the task says to avoid.
- **`topK` as a first-class field, not an `attributes` map key.** The task
  requires the query to "support at least query text and topK"; a typed field is
  safer and the dominant call site (`Query.of(text)`) stays source-compatible.
- **Only `contentId` / `sourceId` filtering.** The generic core cannot host
  Signal Engine business concepts; a filter DSL is out of scope. Business
  filtering is explicitly deferred rather than half-built.
- **Score = `1 − distance`, not a normalised or calibrated similarity.** Simple,
  explicit, monotonic, documented; comparable only within one `RetrievalResult`,
  exactly as `RetrievedPassage.score()` already states.
- **Benchmark not wired into `check`/`build`.** It needs Docker *and* the Python
  `embed` service; it is a measurement, not a gate. Its structural assertions
  (every query ran, deterministic, wiring not broken) are the only pass/fail part.
- **A pre-existing latent bug was observed, not fixed.** A malformed-body request
  to the Python service (missing `Content-Type: application/json`) returns HTTP
  500 instead of a clean `400 AI_REQUEST_INVALID`, because the Task 6A validation
  error handler puts raw `bytes` into the JSON error body. It is **not** on the
  retrieval path (`HttpAiCapabilityInvoker` always sends the header) and fixing it
  is out of scope for this task; noted for a future Python robustness task.

## Explicitly still open (untouched)

**T3** (embedding model / dimension — `embeddinggemma` / 768 is provisional and
now exercised through the full path; still to be re-validated on real ingested
content), **T7** (pgvector ANN index type and parameters — exact scan is the
deliberate baseline), retrieval parameters beyond `topK`, **hybrid retrieval**
(dense + BM25/keyword), **reranking**, **query transformation** (rewriting,
expansion, HyDE), business metadata filtering (area / interest / time), context
assembly and budgeting, generation / answer synthesis, the evaluation subsystem,
the Signal Engine LLM choice, multilingual behaviour, and shared-vs-separate
embeddings for near-duplicate and retrieval. Task 8.3C implements no reranking,
hybrid search, query rewriting, generation, evaluation, frontend, API or
scheduler change.
