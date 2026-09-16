"""The embedding-benchmark harness: metric functions and dataset integrity.

This is NOT a model test — it never touches Ollama. It checks that the retrieval
metrics are computed correctly and that the versioned corpus/queries are
internally consistent, so a real benchmark run rests on a sound harness.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

import pytest

from benchmarks.embedding import metrics

_BENCH_DIR = Path(__file__).resolve().parent.parent / "benchmarks" / "embedding"
_AREAS = {
    "AI & Technology",
    "Markets & Investment",
    "Architecture, Construction & Real Estate",
    "Law & Regulation",
    "Fashion & Clothing",
    "Business & Opportunity Trends",
}


# --- metric functions -------------------------------------------------------


def test_recall_at_k_counts_relevant_in_top_k() -> None:
    ranked = ["a", "b", "c", "d"]
    assert metrics.recall_at_k(ranked, {"c"}, 1) == 0.0
    assert metrics.recall_at_k(ranked, {"c"}, 3) == 1.0
    assert metrics.recall_at_k(ranked, {"a", "c"}, 2) == 0.5
    assert metrics.recall_at_k(ranked, {"a", "c"}, 3) == 1.0


def test_recall_requires_relevant_passages() -> None:
    with pytest.raises(ValueError):
        metrics.recall_at_k(["a"], set(), 1)


def test_reciprocal_rank_and_mrr() -> None:
    assert metrics.reciprocal_rank(["a", "b", "c"], {"c"}) == pytest.approx(1 / 3)
    assert metrics.reciprocal_rank(["a", "b"], {"z"}) == 0.0
    assert metrics.mrr([1.0, 0.5, 0.0]) == pytest.approx(0.5)


def test_ndcg_is_one_for_a_perfect_ranking_and_less_otherwise() -> None:
    grades = {"a": 2, "b": 1}
    perfect = metrics.ndcg_at_k(["a", "b", "c", "d"], grades, 10)
    reversed_order = metrics.ndcg_at_k(["c", "d", "b", "a"], grades, 10)
    assert perfect == pytest.approx(1.0)
    assert 0.0 < reversed_order < 1.0


def test_ndcg_matches_a_hand_computed_example() -> None:
    # ranking [b, a]; grades a=2 (gain 3), b=1 (gain 1)
    # DCG = 1/log2(2) + 3/log2(3) = 1.0 + 1.8927 = 2.8927
    # IDCG = 3/log2(2) + 1/log2(3) = 3.0 + 0.6309 = 3.6309
    value = metrics.ndcg_at_k(["b", "a"], {"a": 2, "b": 1}, 10)
    assert value == pytest.approx(2.8927 / 3.6309, abs=1e-3)


def test_cosine_similarity() -> None:
    assert metrics.cosine_similarity([1.0, 0.0], [1.0, 0.0]) == pytest.approx(1.0)
    assert metrics.cosine_similarity([1.0, 0.0], [0.0, 1.0]) == pytest.approx(0.0)
    assert metrics.cosine_similarity([1.0, 1.0], [-1.0, -1.0]) == pytest.approx(-1.0)
    assert metrics.cosine_similarity([0.0, 0.0], [1.0, 1.0]) == 0.0
    with pytest.raises(ValueError):
        metrics.cosine_similarity([1.0], [1.0, 2.0])


def test_rank_by_cosine_is_deterministic_with_id_tiebreak() -> None:
    query = [1.0, 0.0]
    passages = {"z": [1.0, 0.0], "a": [1.0, 0.0], "m": [0.0, 1.0]}
    assert metrics.rank_by_cosine(query, passages) == ["a", "z", "m"]


# --- dataset integrity ----------------------------------------------------


def _load(name: str) -> dict[str, Any]:
    data: Any = json.loads((_BENCH_DIR / name).read_text(encoding="utf-8"))
    assert isinstance(data, dict)
    return data


def test_corpus_and_queries_are_consistent() -> None:
    corpus = _load("corpus.json")
    queries = _load("queries.json")
    passages = corpus["passages"]
    assert isinstance(passages, list)

    assert corpus["version"] == queries["version"]

    passage_ids = {p["id"] for p in passages}
    assert len(passage_ids) == len(passages) == 36

    by_area: dict[str, int] = {}
    for passage in passages:
        assert passage["area"] in _AREAS
        assert passage["text"].strip()
        assert passage["source_id"] and passage["url"]
        by_area[passage["area"]] = by_area.get(passage["area"], 0) + 1
    assert set(by_area.values()) == {6}

    query_list = queries["queries"]
    assert len({q["id"] for q in query_list}) == len(query_list) == 24
    q_by_area: dict[str, int] = {}
    for query in query_list:
        assert query["area"] in _AREAS
        assert query["query"].strip()
        assert query["relevant"], f"{query['id']} has no relevance labels"
        for relevant in query["relevant"]:
            assert relevant["id"] in passage_ids
            assert relevant["grade"] in (1, 2)
        q_by_area[query["area"]] = q_by_area.get(query["area"], 0) + 1
    assert set(q_by_area.values()) == {4}


def test_every_relevance_label_is_finite_and_graded() -> None:
    queries = _load("queries.json")
    for query in queries["queries"]:
        grades = [r["grade"] for r in query["relevant"]]
        assert all(math.isfinite(g) for g in grades)
        assert any(g == 2 for g in grades), f"{query['id']} has no directly-relevant passage"
