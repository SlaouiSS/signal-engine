"""The shared contract artifacts: the JSON Schema is current, and every committed
example message validates against the Pydantic models (the source of truth). The
backend replays the same example files through its HTTP adapter.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import BaseModel

from app.capabilities.answer import AnswerPayload, AnswerResult
from app.capabilities.embed import EmbedPayload, EmbedResult
from app.capabilities.importance import ImportancePayload, ImportanceResult
from app.capabilities.near_duplicate import NearDuplicatePayload, NearDuplicateResult
from app.capabilities.relevance import RelevancePayload, RelevanceResult
from app.capabilities.semantic_chunk_boundary import (
    SemanticChunkBoundaryPayload,
    SemanticChunkBoundaryResult,
)
from app.capabilities.summarize import SummarizePayload, SummarizeResult
from app.contract import ErrorResponse, RequestEnvelope, SuccessResponse
from app.contract_schema import main as schema_main
from tests.conftest import CONTRACT_DIR

_EXAMPLES = sorted((CONTRACT_DIR / "examples").glob("*.json"))


def _load(name: str) -> dict[str, object]:
    parsed: dict[str, object] = json.loads(
        (CONTRACT_DIR / "examples" / name).read_text(encoding="utf-8")
    )
    return parsed


def test_committed_schema_artifact_is_current() -> None:
    assert schema_main(["--check"]) == 0


def test_every_example_file_is_discovered() -> None:
    names = {path.name for path in _EXAMPLES}
    assert "request.echo.json" in names
    assert "response.success.json" in names
    assert sum(name.startswith("response.error.") for name in names) >= 4


def test_request_example_validates() -> None:
    envelope = RequestEnvelope.model_validate(_load("request.echo.json"))
    assert envelope.capability == "echo"
    assert envelope.contract_version == 1


def test_success_example_round_trips_through_the_model() -> None:
    raw = _load("response.success.json")
    model = SuccessResponse.model_validate(raw)
    assert json.loads(model.model_dump_json(by_alias=True)) == raw


def test_near_duplicate_examples_validate_against_the_capability_models() -> None:
    request = RequestEnvelope.model_validate(_load("request.near-duplicate.json"))
    assert request.capability == "near-duplicate"
    payload = NearDuplicatePayload.model_validate(request.payload)
    comparison_ids = {comparison.id for comparison in payload.comparisons}

    response = SuccessResponse.model_validate(_load("response.near-duplicate-success.json"))
    result = NearDuplicateResult.model_validate(response.result)
    assert {assessment.id for assessment in result.assessments} == comparison_ids
    assert response.meta.prompt_version == "near-duplicate/v1"


_CAPABILITY_MODELS: dict[str, tuple[type[BaseModel], type[BaseModel]]] = {
    "relevance": (RelevancePayload, RelevanceResult),
    "importance": (ImportancePayload, ImportanceResult),
    "summarize": (SummarizePayload, SummarizeResult),
    "answer": (AnswerPayload, AnswerResult),
    "semantic-chunk-boundary": (SemanticChunkBoundaryPayload, SemanticChunkBoundaryResult),
    "embed": (EmbedPayload, EmbedResult),
}


@pytest.mark.parametrize("capability", sorted(_CAPABILITY_MODELS))
def test_signal_pipeline_examples_validate_against_the_capability_models(capability: str) -> None:
    payload_model, result_model = _CAPABILITY_MODELS[capability]

    request = RequestEnvelope.model_validate(_load(f"request.{capability}.json"))
    assert request.capability == capability
    payload_model.model_validate(request.payload)

    response = SuccessResponse.model_validate(_load(f"response.{capability}-success.json"))
    result_model.model_validate(response.result)
    assert response.meta.prompt_version == f"{capability}/v1"


@pytest.mark.parametrize(
    "name",
    [p.name for p in _EXAMPLES if p.name.startswith("response.error.")],
)
def test_error_examples_validate(name: str) -> None:
    model = ErrorResponse.model_validate(_load(name))
    assert model.status == "error"
    assert model.error.correlation_id == model.correlation_id


def test_backend_copies_this_directory(tmp_path: Path) -> None:
    # Documents the single-copy rule: backend/build.gradle.kts copies
    # agents/contract onto the backend test classpath. If this layout changes,
    # update that copy step.
    assert (CONTRACT_DIR / "ai-capability.v1.schema.json").exists()
    assert (CONTRACT_DIR / "examples").is_dir()
