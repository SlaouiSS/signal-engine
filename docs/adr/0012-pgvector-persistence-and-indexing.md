# ADR 0012 — pgvector Persistence and Indexing

Status: Accepted
Date: 2026-09-07
Phase: 2 (docs/11-roadmap.md), Task 8.3B — persist RAG passages and their embeddings

---

## Context

Task 8.1 fixed the RAG contracts, 8.2 added chunking, 8.3A added the embedding
contract and (via a real benchmark) a provisional model. Task 8.3B closes the
loop: turn `IndexableContent` into stored, embedded passages a future retriever
can query.

```
IndexableContent → Chunker → passages → EmbeddingModel → PostgreSQL / pgvector
```

Constraints (from the task and `CLAUDE.md`): PostgreSQL stays the system of
record, no second vector database, no JPA/Hibernate, Java owns persistence,
Python owns embedding generation (the Task 8.3A `embed` capability is reused
unchanged), the generic RAG core stays framework- and persistence-free, and
**retrieval is deliberately not part of this task**.

## Decisions

### 1. pgvector, inside the existing PostgreSQL

Embeddings live in `vector` columns in the same database as the business data
(`docs/03-technical-spec.md` Section 4.3; `docs/05-data-model.md` Section 16). No
Redis, Elasticsearch, Qdrant, or other store. V1 already enabled the extension;
V11 adds the first vector column.

### 2. Persistence is Java's; the generic core stays persistence-free

The RAG core gains a generic write port, `IndexedPassageStore` &mdash; the
symmetric counterpart of `Retriever` &mdash; plus `IndexedPassage`,
`PassagePersistOutcome`, and `StagedIndexingPipeline` (the composition of a
`Chunker`, an `EmbeddingModel` and a store). None of these name Spring, JDBC,
PostgreSQL, pgvector, or a Signal Engine type; `RagCoreBoundaryTest` enforces it.
The PostgreSQL adapter, `PgVectorIndexedPassageStore`, lives in infrastructure
and uses `JdbcClient` directly for the `::vector` cast and the `ON CONFLICT`
upsert &mdash; Spring Data JDBC where it fits (nothing here did), raw JDBC for
the vector operation, **no JPA/Hibernate**.

### 3. Two tables (migration V11)

- **`rag_passage`** &mdash; one row per chunked passage. Primary key is the
  **deterministic passage id** from Task 8.2 (`PassageIds`: SHA-256 over content
  id, chunker id, chunker configuration version, ordinal, text). Columns:
  `content_id`, structured provenance (`source_id` NOT NULL, `document_id`,
  `origin_uri`, `title`), `chunker_id` / `chunker_version`, `passage_ordinal`,
  `passage_text`, and `provenance_attributes` / `metadata` as `JSONB` (the open
  maps, preserved verbatim; not queried yet). Index on `content_id`.
- **`rag_passage_embedding`** &mdash; one row per (passage, embedding model).
  `passage_id` FK (`ON DELETE CASCADE`), `embedding_provider` / `embedding_model`
  / `embedding_model_version` (from the generic `EmbeddingModelDescriptor`),
  `embedding_dimension INTEGER CHECK (= 768)`, `embedding vector(768) NOT NULL`,
  timestamps, `UNIQUE (passage_id, embedding_model, embedding_model_version)`.

No business-level "Document" entity. No triggers, no stored procedures.

### 4. Vector dimension: 768, fixed, explicit

`vector(N)` is fixed-width in pgvector, so the column is `vector(768)` for the
current provisional model (`embeddinggemma`, Task 8.3A). The dimension is **not**
assumed to change in place: a model with a different dimension needs a **new
migration** (a new column or a parallel table). The `embedding_model` columns and
the `CHECK (embedding_dimension = 768)` make that explicit; the RAG core and the
adapter both reject a vector of any other length rather than truncating or
padding.

### 5. Distance metric: cosine — index: none yet (T7 stays open)

Retrieval will use **cosine distance** (`<=>`), consistent with the Task 8.3A
benchmark. This ADR does **not** create an approximate-nearest-neighbour index:
the MVP corpus is small enough for an exact scan, retrieval has no consumer yet,
and the HNSW-vs-IVFFlat choice with its parameters **is** open question **T7**.
The column is ready; a later migration adds
`USING hnsw (embedding vector_cosine_ops)` (or similar) when T7 is decided with
real corpus size.

### 6. Idempotency

The logical identity of an indexed passage is `passageId`
(content + chunker + chunker configuration + ordinal + text) **plus** the
embedding model identity. The store upserts on those natural keys:

- re-running the **same** content, chunker configuration and model &rarr; the row
  is refreshed in place (`UPDATED`), never duplicated;
- a **changed chunker configuration** &rarr; a new `passageId` &rarr; new
  `rag_passage` rows that coexist with the old ones;
- a **changed embedding model** &rarr; new `rag_passage_embedding` rows for the
  same passage that coexist with the old ones.

Nothing is silently deleted. Passage ids are the Task 8.2 deterministic ids, not
random UUIDs.

### 7. Embedding model replacement

`EmbeddingResult` was extended (a concrete incompatibility discovered by this
task &mdash; `CLAUDE.md` rule 17): its `model` field is now a generic
`EmbeddingModelDescriptor(provider, model, version, dimension)` instead of an
opaque `ComponentDescriptor`, so the adapter can persist *which* model produced a
vector. The Ollama adapter fills it from the `embed` response
(`provider`, `model`, capability version). A model swap is: change
`OLLAMA_EMBEDDING_MODEL`, re-index (new `rag_passage_embedding` rows appear,
old ones remain), and &mdash; when the dimension differs &mdash; add a migration
for the new `vector(N)` column. No RAG-core or business-logic change.

### 8. Why retrieval is not here

Retrieval, query embedding, reranking and scoring are a separate concern
(`docs/07-rag.md` Section 7) with their own open questions (T7, retrieval
parameters, hybrid search). Building the write side first keeps each task small
and lets the index shape settle against real stored data before a query path is
committed.

## Consequences

- RAG core: `rag.indexing` gains `IndexedPassage`, `IndexedPassageStore`,
  `PassagePersistOutcome`, `StagedIndexingPipeline`, `IndexingException`;
  `IndexingReport` redesigned with real counts; `IndexingPipeline` javadoc
  updated. `rag.embedding` gains `EmbeddingModelDescriptor`; `EmbeddingResult`'s
  third field changes type. `RagComponentType` +1 value.
- Infrastructure: `PgVectorIndexedPassageStore`, `RagIndexingProperties`,
  `RagIndexingConfiguration`. `AiCapabilityEmbeddingModel` populates the new
  descriptor.
- Migration **V11** (`rag_passage`, `rag_passage_embedding`). No existing table
  touched.
- `docs/07-rag.md` gains Section 23 (Summary → 24); `docs/05-data-model.md`
  Section 16, `docs/03-technical-spec.md` Section 24, and `docs/11-roadmap.md`
  Phase 7 updated.
- No new dependency. `JdbcClient` and Jackson 3 are already on the classpath.
- Nothing in the backend indexes Signal Engine content automatically &mdash; no
  scheduler, no use case, no API. A focused integration test proves the bridge.

## Trade-offs

- **`EmbeddingResult` changed one week after 8.3A.** Justified: persistence
  genuinely needs the concrete provider/model, which the old opaque descriptor
  did not carry. Kept minimal (one field's type).
- **JSONB via a hand-written `JsonMapper`, not a Spring Data JDBC converter.**
  The adapter already uses raw `JdbcClient` for the vector; one more `CAST(... AS
  jsonb)` is consistent and avoids a global converter for a column nothing
  queries yet.
- **No ANN index.** A deliberate deferral to T7, not an omission &mdash; exact
  search is correct (and more accurate) at MVP scale, and premature HNSW tuning
  is what the task says to avoid.
- **`StagedIndexingPipeline` in the core.** Consistent with `StagedRagPipeline`
  (Task 8.1) and `StructureAwareChunker` (Task 8.2): the core ships the generic
  composition; the store behind it is an infrastructure adapter.
- **Per-passage persistence failures are tallied, not thrown.** The
  `IndexingReport` carries `failed` and a note; a whole-content embedding failure
  still propagates. This keeps a partial batch result inspectable without
  swallowing anything.

## Explicitly still open (untouched)

**T3** (embedding model / dimension — `embeddinggemma` / 768 is provisional and
wired; still to be re-validated on real content), **T7** (pgvector index type,
parameters, and the distance metric to pin them to), retrieval parameters,
hybrid retrieval, reranking, context budget, evaluation metrics, LLM judge, the
Signal Engine LLM choice, multilingual behaviour, the re-embedding migration
*procedure*, and shared-vs-separate embeddings for near-duplicate and retrieval.
Task 8.3B implements no retrieval, query embedding, reranking, semantic search,
generation, evaluation, frontend, or API.
