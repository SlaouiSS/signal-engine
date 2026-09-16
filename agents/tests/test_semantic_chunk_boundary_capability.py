"""The semantic-chunk-boundary capability: handler behaviour and the HTTP
contract, all with a deterministic provider (no LLM, no network).

The capability is a thin pass-through: it forwards the library-composed prompt to
the provider and returns the model's raw text for the Java library to validate.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.capabilities.semantic_chunk_boundary import SemanticChunkBoundaryCapability
from app.errors import ProviderTimeout, ProviderUnavailable, RequestInvalid
from app.main import create_app
from app.prompts import PromptLibrary
from app.providers.base import GenerationRequest, GenerationResult, ProviderError
from app.providers.scripted import ScriptedLlmProvider

_CID = "8b1a9953-1f3c-4b8e-9a1d-2c4e6f8a0b2d"
_PROMPT = (
    "Answer with only the identifiers, as [N1,N2,N3], or [].\n\n"
    "[unit 0]\n# Heading one\n[unit 1]\nBody of the first section.\n"
    "[unit 2]\n# Heading two\n[unit 3]\nBody of the second section."
)


def _payload(prompt: str = _PROMPT, **extra: object) -> dict[str, object]:
    return {"prompt": prompt, **extra}


def _capability(
    prompt_library: PromptLibrary, responses: list[str]
) -> SemanticChunkBoundaryCapability:
    return SemanticChunkBoundaryCapability(
        provider=ScriptedLlmProvider(responses), prompts=prompt_library, timeout_seconds=5.0
    )


class _RaisingProvider:
    def __init__(self, error: ProviderError) -> None:
        self._error = error

    @property
    def name(self) -> str:
        return "raising"

    @property
    def model(self) -> str:
        return "raising"

    def generate(self, request: GenerationRequest) -> GenerationResult:
        raise self._error


def test_returns_the_models_raw_text_unchanged(prompt_library: PromptLibrary) -> None:
    handled = _capability(prompt_library, ["[2]"]).handle(_payload(), _CID)

    assert handled.result == {"rawText": "[2]"}
    assert handled.prompt_version == "semantic-chunk-boundary/v1"
    assert handled.provider == "scripted"


def test_surrounding_whitespace_is_trimmed(prompt_library: PromptLibrary) -> None:
    handled = _capability(prompt_library, ["  [1,2,3]\n\n"]).handle(_payload(), _CID)

    assert handled.result == {"rawText": "[1,2,3]"}


def test_empty_list_answer_is_passed_through(prompt_library: PromptLibrary) -> None:
    handled = _capability(prompt_library, ["[]"]).handle(_payload(), _CID)

    assert handled.result == {"rawText": "[]"}


def test_the_library_prompt_reaches_the_provider_verbatim(prompt_library: PromptLibrary) -> None:
    provider = ScriptedLlmProvider(["[2]"])
    SemanticChunkBoundaryCapability(
        provider=provider, prompts=prompt_library, timeout_seconds=5.0
    ).handle(_payload(), _CID)

    assert provider.calls[0].user.strip() == _PROMPT
    assert provider.calls[0].json_output is False


@pytest.mark.parametrize(
    "bad_payload",
    [
        {},
        {"prompt": ""},
        {"prompt": "x", "unexpected": 1},
        {"prompt": "x", "temperature": 5.0},
    ],
)
def test_invalid_payload_is_rejected_before_the_provider(
    prompt_library: PromptLibrary, bad_payload: dict[str, object]
) -> None:
    provider = ScriptedLlmProvider([])
    capability = SemanticChunkBoundaryCapability(
        provider=provider, prompts=prompt_library, timeout_seconds=5.0
    )

    with pytest.raises(RequestInvalid):
        capability.handle(bad_payload, _CID)
    assert provider.calls == []


def test_provider_unavailable_is_mapped(prompt_library: PromptLibrary) -> None:
    capability = SemanticChunkBoundaryCapability(
        provider=_RaisingProvider(ProviderError("down", retryable=True)),
        prompts=prompt_library,
        timeout_seconds=5.0,
    )
    with pytest.raises(ProviderUnavailable):
        capability.handle(_payload(), _CID)


def test_provider_timeout_is_mapped(prompt_library: PromptLibrary) -> None:
    capability = SemanticChunkBoundaryCapability(
        provider=_RaisingProvider(ProviderError("slow", retryable=True, timed_out=True)),
        prompts=prompt_library,
        timeout_seconds=5.0,
    )
    with pytest.raises(ProviderTimeout):
        capability.handle(_payload(), _CID)


def test_http_success_envelope(prompt_library: PromptLibrary) -> None:
    capability = _capability(prompt_library, ["[2]"])
    client = TestClient(create_app({capability.name: capability}))

    response = client.post(
        "/capabilities/semantic-chunk-boundary/v1",
        json={
            "correlationId": _CID,
            "contractVersion": 1,
            "capability": "semantic-chunk-boundary",
            "payload": _payload(),
        },
        headers={"X-Correlation-Id": _CID},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "success"
    assert body["result"] == {"rawText": "[2]"}
    assert body["meta"]["promptVersion"] == "semantic-chunk-boundary/v1"
