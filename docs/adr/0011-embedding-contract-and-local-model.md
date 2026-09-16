# ADR 0011 — Embedding Contract, Local Embedding Provider, and Model Benchmark

Status: Accepted
Date: 2026-09-07
Phase: 2 (docs/11-roadmap.md), Task 8.3A — the embedding abstraction and a real benchmark to inform T3

---

## Context

`docs/07-rag.md` fixes that Python generates embeddings behind an
`EmbeddingProvider` abstraction and Java persists and retrieves them, but leaves
**T3** open: which embedding model, and which vector dimension. Task 8.3A adds
the contract and the local provider, and runs a **real, reproducible benchmark**
on realistic Signal Engine content to inform T3 with evidence rather than a
marketing benchmark. It does **not** touch pgvector, retrieval, hybrid search,
reranking, the Signal Engine LLM choice, or the RAG architecture.

Constraints from the task and `CLAUDE.md`:

- The embedding contract lives in the reusable RAG core and depends on no
  runtime, no framework, no Signal Engine business type, no PostgreSQL/pgvector.
- Reuse the Task 6A transport; do not create a second Ollama HTTP client or a
  duplicate provider abstraction.
- Local-first: run candidates through Ollama on the target machine (32 GB RAM,
  Ryzen 5 5500U, no GPU). Verify a model is real and executable before using it;
  never fabricate benchmark numbers.
- Keep T3 open if the benchmark is inconclusive.

## Decisions

### 1. Embedding contract in `org.signalengine.rag.embedding`

`EmbeddingModel` (`EmbeddingResult embed(EmbeddingRequest)` + `descriptor()`),
`EmbeddingRequest` (`texts` + `TextRole` QUERY/PASSAGE), `EmbeddingResult`
(vectors parallel to inputs, `dimension`, `ComponentDescriptor`), `TextRole`,
`EmbeddingException`. `RagComponentType` gains `EMBEDDING_MODEL`.

`EmbeddingResult`'s canonical constructor is the validation surface: one vector
per input, one consistent positive dimension, every value finite, defensive
copy, **no truncation or padding**. An empty request is a no-op with dimension 0.
The `RagCoreBoundaryTest` (Task 8.1) already scans this package for forbidden
imports.

### 2. `TextRole` for asymmetric retrieval, prefixes owned by the provider

The core states only QUERY vs PASSAGE. Which instruction/prefix a model needs is
the provider adapter's knowledge, exactly as `OllamaLlmProvider` owns
`format: json`. Verified per-model, from each model card:

| Model | Query prefix | Passage prefix |
|---|---|---|
| nomic-embed-text | `search_query: ` | `search_document: ` |
| mxbai-embed-large | `Represent this sentence for searching relevant passages: ` | (none) |
| snowflake-arctic-embed2 | `query: ` | (none) |
| embeddinggemma | `task: search result \| query: ` | `title: none \| text: ` |
| bge-m3, granite-embedding | (none — symmetric) | (none) |

### 3. Transport: the Task 6A capability path, not a new stack

`AiCapabilityEmbeddingModel` (infrastructure) implements `EmbeddingModel` via
`AiCapabilityInvoker` → the new **`embed` v1** Python capability. No Spring AI,
no direct Ollama call from Java, no second HTTP client.

Python: `EmbeddingProvider` is a `Protocol` sibling of `LlmProvider` (the two
ports `docs/06-ai-agents.md` Section 9 already names — no third abstraction).
`OllamaEmbeddingProvider` calls `/api/embed` (batch), sharing the httpx
error-mapping helper with `OllamaLlmProvider`. The `embed` capability has no
prompt; it calls the provider and checks the vectors are well-formed (right
count, one dimension, all finite) before returning them — a malformed embedding
is `AI_OUTPUT_INVALID`.

### 4. Candidate verification

Named candidates: `nemotron-3-embed-1b`, `llama-nemotron-embed-1b-v2`,
`llama-nemotron-embed-300m-v2`, `bge-m3`.

- The three `nemotron-embed` / NeMo Retriever models are **not in the Ollama
  library**. `llama-nemotron-embed-300m-v2` (formerly
  `llama-3.2-nemoretriever-300m-embed-v2`) is on NVIDIA Build, but that hosted
  text-embedding API was **deprecated 2026-05-18**, and no NVIDIA key is
  provisioned. → **NOT AVAILABLE**, not executed, no numbers.
- `bge-m3` and five other Ollama embedding models were pulled and verified
  runnable (dimension, context, license, prefix behaviour) — see `docs/07-rag.md`
  Section 22.3.

### 5. Real benchmark

Corpus: 36 hand-written English passages, 6 per area, article-like. Queries: 24
(4 per area) across six retrieval patterns; **every relevance label
human-authored**, graded 2/1. Held identical across models: corpus, queries,
labels, passage text, cosine similarity, tie-break, K values. Only the model
varies; the one model-specific input is the documented prefix. Metrics:
Recall@{1,3,5,10}, MRR, nDCG@10 (graded); no composite score. Per-model sanity
checks (determinism, dimension consistency, finiteness, long input).

Executed 2026-09-07 on the target machine, Ollama 0.33.3, all six local models.
Results in `agents/benchmarks/embedding/results/2026-09-07.json` and
`docs/07-rag.md` Section 22.5.

### 6. Recommendation: `embeddinggemma`, dimension 768 — provisional, T3 stays open

The benchmark is **near-saturated** (R@5 = R@10 = 1.000 for all six models;
every failure is one query). On MRR and nDCG@10 a top tier of three
(`snowflake-arctic-embed2`, `embeddinggemma`, `granite-embedding:278m`) gets
every query's top hit right. Within that tie, **`embeddinggemma`** is the best
practical fit: perfect nDCG@10, **768 dimensions** (cheaper future pgvector
column/index than the 1024-dim options), fast (123 ms/query), small (622 MB),
2048-token context (headroom over Task 8.2 chunk sizes), built for on-device
retrieval.

**Fallback: `snowflake-arctic-embed2`, dimension 1024** — equal quality, 8192
context, multilingual, Apache-2.0; choose it if the Gemma Terms are unacceptable
for the open-source distribution or if long-context/multilingual becomes a hard
requirement. `granite-embedding:278m` is quality-equivalent and Apache-2.0 but
its 512-token context is too tight.

The default is wired (`OLLAMA_EMBEDDING_MODEL=embeddinggemma`), and
`signal-engine.rag.embedding.expected-dimension` stays `0` until T3 is locked.

**T3 is not closed.** The corpus is 36 hand-written passages; the top-three
margin is within noise. Task 8.3B must re-run the harness on a larger corpus of
real ingested, chunked content and confirm the recommendation before committing
a dimension to the schema.

## Consequences

- New RAG core package `org.signalengine.rag.embedding` (5 types +
  `package-info`); new infra package `org.signalengine.infrastructure.rag.embedding`
  (3 types + `package-info`); `RagComponentType` +1 value; `Provenance` unchanged.
- New Python `embed` capability + `EmbeddingProvider`/`OllamaEmbeddingProvider` +
  config + contract fixtures. `providers/ollama.py` error handling refactored
  into a shared helper (behaviour identical).
- New benchmark harness `agents/benchmarks/embedding/` (corpus, queries, metrics,
  runner, results, README), added to ruff/mypy scope with metric + dataset tests.
- `docs/07-rag.md` gains Section 22; its Summary is renumbered to Section 23.
- `.env.example` and `docker-compose.yml` gain the embedding variables.
- No dependency added. No schema change, no migration, no pgvector.

## Trade-offs

- **A hand-written 36-passage corpus.** Small enough to run in minutes on a CPU,
  varied enough to exercise six retrieval patterns, but near-saturated at R@5 —
  it separates models weakly. Accepted for a first pass; T3 stays open pending a
  real-content re-run.
- **`embeddinggemma` over an Apache-2.0 option.** The Gemma Terms permit
  commercial use and redistribution; the model is pulled at runtime from Ollama,
  not redistributed. Accepted, with `snowflake-arctic-embed2` documented as the
  Apache-2.0 fallback.
- **Benchmark runs through the provider, not the full HTTP capability.** It uses
  `OllamaEmbeddingProvider` directly — the real adapter and real model, one
  fewer network hop. Accepted; the capability HTTP path is covered by contract
  tests.
- **Token usage / memory not measured in detail.** Model size and parameter
  count stand in for the RAM footprint; the task says not to over-engineer
  performance measurement.

## Explicitly still open (untouched)

T3 (embedding model / dimension — a recommendation is made, not locked), T7
(pgvector index and distance), retrieval parameters, hybrid retrieval,
reranking, context budget, evaluation metrics, LLM judge, the Signal Engine LLM
choice, multilingual behaviour, the re-embedding migration path, and shared vs
separate embeddings for near-duplicate and retrieval. Task 8.3A implements no
pgvector, retrieval, generation, evaluation, frontend, API, or persistence.
