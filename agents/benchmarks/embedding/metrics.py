"""Retrieval quality metrics for the embedding benchmark.

Pure functions over a ranked list of passage ids and a query's human-authored
relevance labels. No model, no I/O — unit-tested in ``tests/``.

- ``recall_at_k``  : fraction of a query's relevant passages found in the top K.
- ``reciprocal_rank`` / ``mrr`` : 1 / rank of the first relevant passage.
- ``ndcg_at_k``   : normalized discounted cumulative gain with graded relevance,
                    gain = ``2**grade - 1``.

No composite score is produced (docs/09-evaluation.md Section 10).
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence


def recall_at_k(ranked_ids: Sequence[str], relevant_ids: set[str], k: int) -> float:
    if not relevant_ids:
        raise ValueError("recall is undefined with no relevant passages")
    top_k = set(ranked_ids[:k])
    return len(top_k & relevant_ids) / len(relevant_ids)


def reciprocal_rank(ranked_ids: Sequence[str], relevant_ids: set[str]) -> float:
    for position, passage_id in enumerate(ranked_ids, start=1):
        if passage_id in relevant_ids:
            return 1.0 / position
    return 0.0


def mrr(reciprocal_ranks: Sequence[float]) -> float:
    return sum(reciprocal_ranks) / len(reciprocal_ranks) if reciprocal_ranks else 0.0


def _dcg(gains: Sequence[float]) -> float:
    return sum(gain / math.log2(position + 1) for position, gain in enumerate(gains, start=1))


def ndcg_at_k(ranked_ids: Sequence[str], id_to_grade: dict[str, int], k: int) -> float:
    if not id_to_grade:
        raise ValueError("nDCG is undefined with no graded passages")
    ranked_gains = [2 ** id_to_grade.get(passage_id, 0) - 1 for passage_id in ranked_ids[:k]]
    ideal_gains = sorted((2**grade - 1 for grade in id_to_grade.values()), reverse=True)[:k]
    ideal = _dcg(ideal_gains)
    return _dcg(ranked_gains) / ideal if ideal > 0 else 0.0


def cosine_similarity(a: Sequence[float], b: Sequence[float]) -> float:
    if len(a) != len(b):
        raise ValueError(f"dimension mismatch: {len(a)} vs {len(b)}")
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(y * y for y in b))
    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0
    return dot / (norm_a * norm_b)


def rank_by_cosine(
    query_vector: Sequence[float], passage_vectors: Mapping[str, Sequence[float]]
) -> list[str]:
    """Passage ids ordered by descending cosine similarity to the query. Ties are
    broken by passage id so the ranking is deterministic."""
    scored = [
        (cosine_similarity(query_vector, vector), passage_id)
        for passage_id, vector in passage_vectors.items()
    ]
    scored.sort(key=lambda pair: (-pair[0], pair[1]))
    return [passage_id for _, passage_id in scored]
