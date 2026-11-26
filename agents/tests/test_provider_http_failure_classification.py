"""How a provider HTTP failure is classified and carried across the Java/Python boundary.

docs/03-technical-spec.md Section 13.2 makes only "provider transport/5xx" retryable. A permanent
HTTP status must therefore not be retryable, and the failures that mean the provider
*configuration* is wrong (bad credentials, retired model) must be told apart from the failures
that are specific to one request (docs/adr/0018-pipeline-reliability.md).
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from app.capabilities.embed import EmbedCapability
from app.capabilities.structured import generate_structured
from app.errors import ProviderUnavailable, to_capability_error
from app.main import create_app
from app.prompts import PromptLibrary
from app.providers.base import EmbeddingRequest, GenerationRequest, ProviderError
from app.providers.nvidia import NvidiaLlmProvider
from app.providers.ollama import OllamaEmbeddingProvider, OllamaLlmProvider

TRANSIENT_STATUSES = [408, 425, 429, 500, 502, 503, 504]
REQUEST_SPECIFIC_STATUSES = [400, 409, 413, 422]
CONFIGURATION_STATUSES = [401, 402, 403, 404, 410]

_CID = "11111111-2222-3333-4444-555555555555"
_GENERATION = GenerationRequest(system="s", user="u", timeout_seconds=1.0)
_EMBEDDING = EmbeddingRequest(texts=["a text"], role="passage", timeout_seconds=1.0)


def _respond_with_status(monkeypatch: pytest.MonkeyPatch, status: int) -> None:
    def reply(url: str, **kwargs: Any) -> httpx.Response:
        return httpx.Response(status, text="body", request=httpx.Request("POST", url))

    monkeypatch.setattr(httpx, "post", reply)


def _call_nvidia() -> None:
    NvidiaLlmProvider(base_url="https://nvidia.test/v1", model="m", api_key="k").generate(
        _GENERATION
    )


def _call_ollama_chat() -> None:
    OllamaLlmProvider(base_url="http://ollama.test", model="m").generate(_GENERATION)


def _call_ollama_embedding() -> None:
    OllamaEmbeddingProvider(base_url="http://ollama.test", model="embeddinggemma").embed(_EMBEDDING)


ADAPTER_CALLS: list[Callable[[], None]] = [_call_nvidia, _call_ollama_chat, _call_ollama_embedding]
ADAPTER_IDS = ["nvidia", "ollama-chat", "ollama-embedding"]


def _failure_of(call: Callable[[], None]) -> ProviderError:
    with pytest.raises(ProviderError) as caught:
        call()
    return caught.value


@pytest.mark.parametrize("call", ADAPTER_CALLS, ids=ADAPTER_IDS)
@pytest.mark.parametrize("status", TRANSIENT_STATUSES)
def test_transient_http_status_is_retryable(
    monkeypatch: pytest.MonkeyPatch, call: Callable[[], None], status: int
) -> None:
    _respond_with_status(monkeypatch, status)

    failure = _failure_of(call)

    assert failure.retryable is True
    assert failure.http_status == status
    assert failure.rejects_configuration is False


@pytest.mark.parametrize("call", ADAPTER_CALLS, ids=ADAPTER_IDS)
@pytest.mark.parametrize("status", REQUEST_SPECIFIC_STATUSES)
def test_request_specific_http_status_is_permanent_but_not_a_configuration_failure(
    monkeypatch: pytest.MonkeyPatch, call: Callable[[], None], status: int
) -> None:
    _respond_with_status(monkeypatch, status)

    failure = _failure_of(call)

    assert failure.retryable is False
    assert failure.http_status == status
    assert failure.rejects_configuration is False


@pytest.mark.parametrize("call", ADAPTER_CALLS, ids=ADAPTER_IDS)
@pytest.mark.parametrize("status", CONFIGURATION_STATUSES)
def test_credential_or_model_http_status_is_a_permanent_configuration_failure(
    monkeypatch: pytest.MonkeyPatch, call: Callable[[], None], status: int
) -> None:
    _respond_with_status(monkeypatch, status)

    failure = _failure_of(call)

    assert failure.retryable is False
    assert failure.http_status == status
    assert failure.rejects_configuration is True


@pytest.mark.parametrize("call", ADAPTER_CALLS, ids=ADAPTER_IDS)
def test_a_connection_failure_stays_retryable_without_an_http_status(
    monkeypatch: pytest.MonkeyPatch, call: Callable[[], None]
) -> None:
    def refuse(url: str, **kwargs: Any) -> httpx.Response:
        raise httpx.ConnectError("refused")

    monkeypatch.setattr(httpx, "post", refuse)

    failure = _failure_of(call)

    assert failure.retryable is True
    assert failure.http_status is None


# --- carried into the capability error ---------------------------------------


def test_a_configuration_failure_maps_to_a_non_retryable_provider_error_with_its_scope() -> None:
    error = to_capability_error(
        ProviderError("provider returned HTTP 404", retryable=False, http_status=404)
    )

    assert isinstance(error, ProviderUnavailable)
    assert error.code == "AI_PROVIDER_UNAVAILABLE"
    assert error.retryable is False
    assert error.details == {"providerStatus": 404, "providerFailureScope": "configuration"}


def test_a_request_specific_failure_maps_with_the_request_scope() -> None:
    error = to_capability_error(
        ProviderError("provider returned HTTP 422", retryable=False, http_status=422)
    )

    assert error.retryable is False
    assert error.details == {"providerStatus": 422, "providerFailureScope": "request"}


def test_a_transient_failure_stays_retryable_and_carries_no_scope() -> None:
    error = to_capability_error(
        ProviderError("provider returned HTTP 503", retryable=True, http_status=503)
    )

    assert error.retryable is True
    assert error.details == {"providerStatus": 503}


def test_a_timeout_still_maps_to_the_timeout_error() -> None:
    error = to_capability_error(ProviderError("slow", retryable=True, timed_out=True))

    assert error.code == "AI_PROVIDER_TIMEOUT"
    assert error.retryable is True


# --- reaches every capability path --------------------------------------------


class _AlwaysRejectingProvider:
    name = "rejecting"
    model = "rejecting"

    def __init__(self, status: int) -> None:
        self._status = status

    def generate(self, request: GenerationRequest) -> Any:
        raise ProviderError("rejected", retryable=False, http_status=self._status)

    def embed(self, request: EmbeddingRequest) -> Any:
        raise ProviderError("rejected", retryable=False, http_status=self._status)


def test_structured_generation_surfaces_a_configuration_failure(
    prompt_library: PromptLibrary,
) -> None:
    prompt = prompt_library.load("echo", 1).render(text="hi")

    with pytest.raises(ProviderUnavailable) as caught:
        generate_structured(
            provider=_AlwaysRejectingProvider(410),
            prompt=prompt,
            repair_prompt=prompt_library.load("repair", 1),
            result_model=_ResultModel,
            timeout_seconds=1,
        )

    assert caught.value.retryable is False
    assert caught.value.details == {"providerStatus": 410, "providerFailureScope": "configuration"}


def test_the_error_envelope_carries_retryable_false_and_the_provider_scope() -> None:
    capability = EmbedCapability(provider=_AlwaysRejectingProvider(404), timeout_seconds=1.0)
    client = TestClient(create_app({"embed": capability}), raise_server_exceptions=False)

    response = client.post(
        "/capabilities/embed/v1",
        json={
            "correlationId": _CID,
            "contractVersion": 1,
            "capability": "embed",
            "payload": {"texts": ["a text"], "role": "query"},
        },
        headers={"X-Correlation-Id": _CID},
    )

    error = response.json()["error"]
    assert error["code"] == "AI_PROVIDER_UNAVAILABLE"
    assert error["category"] == "provider"
    assert error["retryable"] is False
    assert error["details"] == {"providerStatus": 404, "providerFailureScope": "configuration"}


from pydantic import BaseModel, ConfigDict  # noqa: E402


class _ResultModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    echoed: str
