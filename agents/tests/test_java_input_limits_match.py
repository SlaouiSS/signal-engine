"""The limits the Java backend checks before calling are the ones this service enforces
(docs/adr/0019-pipeline-automation.md). If they drift, content is either refused here after all, or
set aside by Java although it would have been accepted."""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.capabilities import (
    answer,
    embed,
    importance,
    near_duplicate,
    relevance,
    semantic_chunk_boundary,
    summarize,
)

_JAVA_LIMITS = (
    Path(__file__).resolve().parents[2]
    / "backend/src/main/java/org/signalengine/application/ai/AiInputLimits.java"
).read_text(encoding="utf-8")


def _java_limit(name: str) -> int:
    declared = re.search(rf"\b{name} = ([\d_]+);", _JAVA_LIMITS)
    assert declared is not None, f"{name} is not declared in AiInputLimits"
    return int(declared.group(1).replace("_", ""))


@pytest.mark.parametrize(
    ("java_name", "python_limit"),
    [
        ("NEAR_DUPLICATE_TEXT", near_duplicate._MAX_TEXT_CHARS),
        ("RELEVANCE_TEXT", relevance._MAX_TEXT_CHARS),
        ("IMPORTANCE_TEXT", importance._MAX_TEXT_CHARS),
        ("SUMMARY_CONTENT", summarize._MAX_CONTENT_CHARS),
        ("EMBED_TEXT", embed._MAX_TEXT_CHARS),
        ("EMBED_TEXTS_PER_CALL", embed._MAX_TEXTS),
        ("CHUNK_BOUNDARY_PROMPT", semantic_chunk_boundary._MAX_PROMPT_CHARS),
        ("ANSWER_QUESTION", answer._MAX_QUESTION_CHARS),
        ("ANSWER_PASSAGE_TEXT", answer._MAX_PASSAGE_CHARS),
        ("ANSWER_PASSAGES", answer._MAX_PASSAGES),
    ],
)
def test_a_java_limit_equals_the_python_one(java_name: str, python_limit: int) -> None:
    assert _java_limit(java_name) == python_limit


@pytest.mark.parametrize(
    ("java_name", "model", "field"),
    [
        ("SUMMARY_SOURCE_NAME", summarize._Source, "name"),
        ("SUMMARY_SOURCE_URL", summarize._Source, "url"),
    ],
)
def test_a_java_source_limit_equals_the_declared_field_limit(
    java_name: str, model: type, field: str
) -> None:
    schema = model.model_json_schema()["properties"][field]  # type: ignore[attr-defined]
    declared = schema.get("maxLength") or next(
        option["maxLength"] for option in schema["anyOf"] if "maxLength" in option
    )
    assert _java_limit(java_name) == declared
