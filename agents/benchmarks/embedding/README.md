# Embedding-model benchmark (Task 8.3A)

A **real, reproducible** benchmark that runs candidate embedding models through
local inference and measures retrieval quality and performance on realistic
Signal Engine content. It exists to inform open question **T3** (which embedding
model, and vector dimension) with evidence rather than a marketing benchmark.

## What it is

- `corpus.json` — 36 hand-written English passages resembling normalized,
  chunked article content, 6 per area of interest. Versioned by date.
- `queries.json` — 24 search queries, 4 per area, covering six retrieval
  patterns (exact concept, paraphrase, implicit semantic relation, terminology
  variation, cross-wording, specific fact). Every relevance label is
  **human-authored**; no LLM assigned relevance. Graded: 2 = directly relevant,
  1 = partially relevant.
- `metrics.py` — Recall@{1,3,5,10}, MRR, nDCG@10, cosine similarity. Pure
  functions, unit-tested in `agents/tests/test_embedding_benchmark.py`. No
  composite score.
- `run_benchmark.py` — the runner. For each model: warm-up, technical sanity
  checks, embed all passages once (`role=passage`), embed each query
  individually (`role=query`), rank by cosine, compute the metrics, record
  timings. Writes `results/<UTC-date>.json` and `results/latest.json`.

## Methodology (held identical across models)

| Held constant | Value |
|---|---|
| Corpus, queries, relevance labels | `corpus.json` / `queries.json` (same version) |
| Passage text fed to the model | `"{title}\n\n{text}"` |
| Similarity | cosine, L2-agnostic (`dot / (‖a‖·‖b‖)`) |
| Ranking tie-break | passage id ascending (deterministic) |
| Top-K reported | 1, 3, 5, 10 |
| Transport | the project's `OllamaEmbeddingProvider` (no second HTTP client) |

The **only** variable is the embedding model. Query vs. passage prefixes are the
one model-specific input, applied exactly as each model's card documents (see
`app/providers/ollama.py`), because using a model as intended is not tuning.

## Running it

Ollama must be running locally with the candidate models pulled:

```
ollama pull bge-m3 nomic-embed-text mxbai-embed-large snowflake-arctic-embed2 embeddinggemma granite-embedding:278m
cd agents
uv run python -m benchmarks.embedding.run_benchmark              # all models
uv run python -m benchmarks.embedding.run_benchmark embeddinggemma bge-m3   # a subset
```

A model that cannot be reached or executed is recorded with status `BLOCKED`
and the error; its numbers are never fabricated.

## Candidates NOT benchmarked

`nemotron-3-embed-1b`, `llama-nemotron-embed-1b-v2`,
`llama-nemotron-embed-300m-v2` (a.k.a. `llama-3.2-nemoretriever-*-embed`) are
**not in the Ollama library**, and NVIDIA Build's hosted text-embedding API was
**deprecated on 2026-05-18** (before this task). With no local path and no live
endpoint they could not be executed; see `docs/07-rag.md` Section 22 and
`docs/adr/0011-embedding-contract-and-local-model.md`.
