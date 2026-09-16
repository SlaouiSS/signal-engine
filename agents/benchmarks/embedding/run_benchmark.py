"""Real embedding-model benchmark for Signal Engine (Task 8.3A).

Runs each candidate embedding model through the project's own
``OllamaEmbeddingProvider`` (no second HTTP client), against the versioned corpus
and human-authored queries in this directory, and reports retrieval quality and
performance. Every number comes from actual local model inference.

    uv run python -m benchmarks.embedding.run_benchmark            # all models
    uv run python -m benchmarks.embedding.run_benchmark bge-m3 nomic-embed-text

Results are written to ``results/<UTC-date>.json`` and ``results/latest.json``.
Nothing here is executed by pytest; the metric functions are tested separately.
"""

from __future__ import annotations

import json
import math
import sys
import time
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.providers.base import EmbeddingRequest
from app.providers.ollama import OllamaEmbeddingProvider
from benchmarks.embedding import metrics

_HERE = Path(__file__).resolve().parent
_RESULTS_DIR = _HERE / "results"
_OLLAMA_URL = "http://127.0.0.1:11434"
_TIMEOUT_SECONDS = 120.0
_K_VALUES = (1, 3, 5, 10)

# Candidate models verified as pullable and executable on Ollama (Task 8.3A
# Section 2). The three NeMo Retriever / nemotron-embed candidates are NOT here:
# they are not in the Ollama library and the NVIDIA Build hosted embedding API
# was deprecated on 2026-05-18.
_MODELS: tuple[str, ...] = (
    "bge-m3",
    "nomic-embed-text",
    "mxbai-embed-large",
    "snowflake-arctic-embed2",
    "embeddinggemma",
    "granite-embedding:278m",
)


@dataclass
class ModelRun:
    model: str
    status: str
    dimension: int = 0
    recall_at_k: dict[int, float] = field(default_factory=dict)
    mrr: float = 0.0
    ndcg_at_10: float = 0.0
    per_area: dict[str, dict[str, float]] = field(default_factory=dict)
    per_pattern: dict[str, dict[str, float]] = field(default_factory=dict)
    performance: dict[str, float] = field(default_factory=dict)
    sanity: dict[str, Any] = field(default_factory=dict)
    detail: list[dict[str, Any]] = field(default_factory=list)
    error: str = ""


def _load(name: str) -> dict[str, Any]:
    data: Any = json.loads((_HERE / name).read_text(encoding="utf-8"))
    assert isinstance(data, dict)
    return data


def _validate(corpus: dict[str, Any], queries: dict[str, Any]) -> None:
    ids = {p["id"] for p in corpus["passages"]}
    if corpus["version"] != queries["version"]:
        raise SystemExit(
            f"corpus version {corpus['version']} != queries version {queries['version']}"
        )
    for query in queries["queries"]:
        for relevant in query["relevant"]:
            if relevant["id"] not in ids:
                raise SystemExit(f"query {query['id']} references unknown passage {relevant['id']}")


def _sanity_checks(provider: OllamaEmbeddingProvider, dimension: int) -> dict[str, Any]:
    probe = "Signal Engine benchmark sanity probe: a short factual sentence."
    first = provider.embed(
        EmbeddingRequest(texts=[probe], role="passage", timeout_seconds=_TIMEOUT_SECONDS)
    )
    second = provider.embed(
        EmbeddingRequest(texts=[probe], role="passage", timeout_seconds=_TIMEOUT_SECONDS)
    )
    repeat_similarity = metrics.cosine_similarity(first.vectors[0], second.vectors[0])

    long_text = ("Housing demand softened as mortgage rates rose. " * 400).strip()
    long_result = provider.embed(
        EmbeddingRequest(texts=[long_text], role="passage", timeout_seconds=_TIMEOUT_SECONDS)
    )

    query_vec = provider.embed(
        EmbeddingRequest(texts=[probe], role="query", timeout_seconds=_TIMEOUT_SECONDS)
    )
    all_finite = all(
        _is_finite(v) for result in (first, second, long_result, query_vec) for v in result.vectors
    )
    return {
        "dimension_consistent": len({first.dimension, second.dimension, query_vec.dimension}) == 1
        and first.dimension == dimension,
        "repeat_cosine_similarity": round(repeat_similarity, 6),
        "all_values_finite": all_finite,
        "long_input_dimension": long_result.dimension,
        "query_vs_passage_cosine_on_same_text": round(
            metrics.cosine_similarity(first.vectors[0], query_vec.vectors[0]), 6
        ),
    }


def _is_finite(vector: list[float]) -> bool:
    return all(math.isfinite(x) for x in vector)


def _benchmark_model(model: str, corpus: dict[str, Any], queries: dict[str, Any]) -> ModelRun:
    provider = OllamaEmbeddingProvider(base_url=_OLLAMA_URL, model=model)
    passages = corpus["passages"]
    passage_texts = [f"{p['title']}\n\n{p['text']}" for p in passages]
    passage_ids = [p["id"] for p in passages]
    query_list = queries["queries"]

    try:
        cold_start = time.monotonic()
        provider.embed(
            EmbeddingRequest(texts=["warm up"], role="query", timeout_seconds=_TIMEOUT_SECONDS)
        )
        load_seconds = time.monotonic() - cold_start

        sanity = _sanity_checks(provider, dimension=0)  # dimension filled after first real call

        corpus_start = time.monotonic()
        passage_result = provider.embed(
            EmbeddingRequest(texts=passage_texts, role="passage", timeout_seconds=_TIMEOUT_SECONDS)
        )
        corpus_seconds = time.monotonic() - corpus_start
        dimension = passage_result.dimension
        sanity["dimension_consistent"] = sanity["long_input_dimension"] == dimension

        query_latencies: list[float] = []
        query_vectors: list[list[float]] = []
        for query in query_list:
            started = time.monotonic()
            result = provider.embed(
                EmbeddingRequest(
                    texts=[query["query"]], role="query", timeout_seconds=_TIMEOUT_SECONDS
                )
            )
            query_latencies.append(time.monotonic() - started)
            query_vectors.append(result.vectors[0])
    except Exception as failure:  # noqa: BLE001 — a real run may fail for many reasons
        return ModelRun(model=model, status="BLOCKED", error=f"{type(failure).__name__}: {failure}")

    passage_vectors = dict(zip(passage_ids, passage_result.vectors, strict=True))

    reciprocal_ranks: list[float] = []
    recall_totals: dict[int, list[float]] = {k: [] for k in _K_VALUES}
    ndcg_scores: list[float] = []
    per_area: dict[str, list[dict[str, float]]] = {}
    per_pattern: dict[str, list[dict[str, float]]] = {}
    detail: list[dict[str, Any]] = []

    for query, query_vector in zip(query_list, query_vectors, strict=True):
        ranked = metrics.rank_by_cosine(query_vector, passage_vectors)
        relevant_ids = {r["id"] for r in query["relevant"]}
        grades = {r["id"]: r["grade"] for r in query["relevant"]}

        per_k = {k: metrics.recall_at_k(ranked, relevant_ids, k) for k in _K_VALUES}
        rr = metrics.reciprocal_rank(ranked, relevant_ids)
        ndcg = metrics.ndcg_at_k(ranked, grades, 10)

        reciprocal_ranks.append(rr)
        ndcg_scores.append(ndcg)
        for k in _K_VALUES:
            recall_totals[k].append(per_k[k])
        row = {**{f"recall@{k}": per_k[k] for k in _K_VALUES}, "rr": rr, "ndcg@10": ndcg}
        per_area.setdefault(query["area"], []).append(row)
        per_pattern.setdefault(query["pattern"], []).append(row)
        detail.append(
            {
                "query_id": query["id"],
                "area": query["area"],
                "pattern": query["pattern"],
                "top5": ranked[:5],
                "relevant": sorted(relevant_ids),
                "first_relevant_rank": _first_rank(ranked, relevant_ids),
                "ndcg@10": round(ndcg, 4),
            }
        )

    return ModelRun(
        model=model,
        status="OK",
        dimension=dimension,
        recall_at_k={k: _mean(recall_totals[k]) for k in _K_VALUES},
        mrr=metrics.mrr(reciprocal_ranks),
        ndcg_at_10=_mean(ndcg_scores),
        per_area={area: _summarise(rows) for area, rows in sorted(per_area.items())},
        per_pattern={pattern: _summarise(rows) for pattern, rows in sorted(per_pattern.items())},
        performance={
            "model_load_seconds": round(load_seconds, 3),
            "corpus_embed_seconds": round(corpus_seconds, 3),
            "passages": len(passages),
            "amortized_passage_latency_ms": round(corpus_seconds / len(passages) * 1000, 1),
            "avg_query_latency_ms": round(_mean(query_latencies) * 1000, 1),
            "max_query_latency_ms": round(max(query_latencies) * 1000, 1),
        },
        sanity=sanity,
        detail=detail,
    )


def _first_rank(ranked: list[str], relevant_ids: set[str]) -> int | None:
    for position, passage_id in enumerate(ranked, start=1):
        if passage_id in relevant_ids:
            return position
    return None


def _mean(values: list[float]) -> float:
    return round(sum(values) / len(values), 4) if values else 0.0


def _summarise(rows: list[dict[str, float]]) -> dict[str, float]:
    return {
        "queries": float(len(rows)),
        "recall@1": _mean([r["recall@1"] for r in rows]),
        "recall@3": _mean([r["recall@3"] for r in rows]),
        "recall@5": _mean([r["recall@5"] for r in rows]),
        "recall@10": _mean([r["recall@10"] for r in rows]),
        "mrr": _mean([r["rr"] for r in rows]),
        "ndcg@10": _mean([r["ndcg@10"] for r in rows]),
    }


def _markdown_table(runs: list[ModelRun]) -> str:
    header = (
        "| Model | Runtime | Dim | R@1 | R@3 | R@5 | R@10 | MRR | nDCG@10 "
        "| Query ms | Corpus s | Status |\n"
        "|---|---|---|---|---|---|---|---|---|---|---|---|"
    )
    lines = [header]
    for run in runs:
        if run.status != "OK":
            lines.append(
                f"| {run.model} | ollama | - | - | - | - | - | - | - | - | - | {run.status} |"
            )
            continue
        p = run.performance
        lines.append(
            f"| {run.model} | ollama | {run.dimension} "
            f"| {run.recall_at_k[1]:.3f} | {run.recall_at_k[3]:.3f} | {run.recall_at_k[5]:.3f} "
            f"| {run.recall_at_k[10]:.3f} | {run.mrr:.3f} | {run.ndcg_at_10:.3f} "
            f"| {p['avg_query_latency_ms']:.0f} | {p['corpus_embed_seconds']:.1f} | OK |"
        )
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    corpus = _load("corpus.json")
    queries = _load("queries.json")
    _validate(corpus, queries)

    selected = [m for m in _MODELS if m in argv] if argv else list(_MODELS)
    print(
        f"corpus {corpus['version']}: {len(corpus['passages'])} passages, "
        f"{len(queries['queries'])} queries, models: {', '.join(selected)}\n"
    )

    runs: list[ModelRun] = []
    for model in selected:
        print(f"--- {model} ---", flush=True)
        run = _benchmark_model(model, corpus, queries)
        if run.status == "OK":
            perf = run.performance
            print(
                f"    dim={run.dimension} "
                f"R@1={run.recall_at_k[1]:.3f} R@5={run.recall_at_k[5]:.3f} "
                f"MRR={run.mrr:.3f} nDCG@10={run.ndcg_at_10:.3f} "
                f"query={perf['avg_query_latency_ms']:.0f}ms",
                flush=True,
            )
        else:
            print(f"    {run.status}: {run.error}", flush=True)
        runs.append(run)

    table = _markdown_table(runs)
    print("\n" + table + "\n")

    stamp = datetime.now(UTC).strftime("%Y-%m-%d")
    payload = {
        "generated_utc": datetime.now(UTC).isoformat(timespec="seconds"),
        "corpus_version": corpus["version"],
        "queries_version": queries["version"],
        "passages": len(corpus["passages"]),
        "queries": len(queries["queries"]),
        "similarity": "cosine",
        "k_values": list(_K_VALUES),
        "ollama_url": _OLLAMA_URL,
        "markdown_table": table,
        "runs": [asdict(run) for run in runs],
    }
    _RESULTS_DIR.mkdir(exist_ok=True)
    (_RESULTS_DIR / f"{stamp}.json").write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (_RESULTS_DIR / "latest.json").write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(f"wrote results/{stamp}.json and results/latest.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
