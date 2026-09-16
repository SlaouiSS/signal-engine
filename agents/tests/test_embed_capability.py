"""The embed capability: handler behaviour and the HTTP contract, all with a
deterministic in-process embedding provider (no model, no network)."""

from __future__ import annotations

import math

import pytest
from fastapi.testclient import TestClient

from app.capabilities.embed import EmbedCapability
from app.errors import OutputInvalid, ProviderTimeout, ProviderUnavailable, RequestInvalid
from app.main import create_app
from app.providers.base import EmbeddingRequest, EmbeddingResult, ProviderError

_CID = "8b1a9953-1f3c-4b8e-9a1d-2c4e6f8a0b2d"


class _FakeEmbeddingProvider:
    def __init__(self, vectors: list[list[float]] | None = None) -> None:
        self._vectors = vectors
        self.calls: list[EmbeddingRequest] = []

    @property
    def name(self) -> str:
        return "fake"

    @property
    def model(self) -> str:
        return "fake-embed"

    def embed(self, request: EmbeddingRequest) -> EmbeddingResult:
        self.calls.append(request)
        vectors = self._vectors
        if vectors is None:
            vectors = [
                [float(i), float(i) + 0.5, float(i) - 0.5] for i, _ in enumerate(request.texts)
            ]
        dimension = len(vectors[0]) if vectors else 0
        return EmbeddingResult(
            vectors=vectors,
            dimension=dimension,
            provider="fake",
            model="fake-embed",
            duration_millis=3,
        )


class _RaisingEmbeddingProvider:
    def __init__(self, error: ProviderError) -> None:
        self._error = error

    @property
    def name(self) -> str:
        return "raising"

    @property
    def model(self) -> str:
        return "raising"

    def embed(self, request: EmbeddingRequest) -> EmbeddingResult:
        raise self._error


def _capability(provider: object) -> EmbedCapability:
    return EmbedCapability(provider=provider, timeout_seconds=5.0)  # type: ignore[arg-type]


def test_returns_one_vector_per_text_with_dimension_and_model() -> None:
    provider = _FakeEmbeddingProvider()
    handled = _capability(provider).handle({"texts": ["alpha", "bravo"], "role": "passage"}, _CID)

    assert len(handled.result["vectors"]) == 2
    assert handled.result["dimension"] == 3
    assert handled.result["model"] == "fake-embed"
    assert handled.prompt_version == "embed/v1"
    assert handled.provider == "fake"
    assert provider.calls[0].role == "passage"


def test_query_role_is_passed_through() -> None:
    provider = _FakeEmbeddingProvider()
    _capability(provider).handle({"texts": ["a search"], "role": "query"}, _CID)
    assert provider.calls[0].role == "query"


@pytest.mark.parametrize(
    "bad",
    [
        {"texts": [], "role": "query"},
        {"texts": ["x"], "role": "document"},
        {"texts": ["x"]},
        {"texts": ["  "], "role": "query"},
        {"texts": ["x"], "role": "query", "extra": 1},
    ],
)
def test_invalid_payload_is_rejected_before_the_provider(bad: dict[str, object]) -> None:
    provider = _FakeEmbeddingProvider()
    with pytest.raises(RequestInvalid):
        _capability(provider).handle(bad, _CID)
    assert provider.calls == []


def test_provider_unavailable_and_timeout_map_to_typed_errors() -> None:
    with pytest.raises(ProviderUnavailable):
        _capability(_RaisingEmbeddingProvider(ProviderError("down", retryable=True))).handle(
            {"texts": ["x"], "role": "query"}, _CID
        )
    with pytest.raises(ProviderTimeout):
        _capability(
            _RaisingEmbeddingProvider(ProviderError("slow", retryable=True, timed_out=True))
        ).handle({"texts": ["x"], "role": "query"}, _CID)


def test_wrong_vector_count_is_output_invalid() -> None:
    provider = _FakeEmbeddingProvider(vectors=[[1.0, 2.0]])
    with pytest.raises(OutputInvalid):
        _capability(provider).handle({"texts": ["a", "b"], "role": "passage"}, _CID)


def test_inconsistent_dimensions_are_output_invalid() -> None:
    provider = _FakeEmbeddingProvider(vectors=[[1.0, 2.0], [1.0, 2.0, 3.0]])
    with pytest.raises(OutputInvalid):
        _capability(provider).handle({"texts": ["a", "b"], "role": "passage"}, _CID)


def test_non_finite_values_are_output_invalid() -> None:
    provider = _FakeEmbeddingProvider(vectors=[[1.0, math.nan, 3.0]])
    with pytest.raises(OutputInvalid):
        _capability(provider).handle({"texts": ["a"], "role": "passage"}, _CID)


def test_http_success_envelope() -> None:
    capability = _capability(_FakeEmbeddingProvider())
    client = TestClient(create_app({capability.name: capability}))

    response = client.post(
        "/capabilities/embed/v1",
        json={
            "correlationId": _CID,
            "contractVersion": 1,
            "capability": "embed",
            "payload": {"texts": ["hello"], "role": "query"},
        },
        headers={"X-Correlation-Id": _CID},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "success"
    assert body["result"]["dimension"] == 3
    assert body["meta"]["promptVersion"] == "embed/v1"
