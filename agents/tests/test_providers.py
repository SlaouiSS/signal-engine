"""Provider adapters: the scripted fake, and the Ollama HTTP adapter with
``httpx.post`` monkeypatched (no network, no Ollama)."""

from __future__ import annotations

import re
import time
from typing import Any

import httpx
import pytest

from app.providers.base import EmbeddingRequest, GenerationRequest, ProviderError
from app.providers.nvidia import NvidiaLlmProvider
from app.providers.ollama import OllamaEmbeddingProvider, OllamaLlmProvider
from app.providers.scripted import ScriptedLlmProvider

_REQUEST = GenerationRequest(system="s", user="u", timeout_seconds=1.0)
_TEXT_REQUEST = GenerationRequest(system="s", user="u", json_output=False, timeout_seconds=1.0)


def _ollama() -> OllamaLlmProvider:
    return OllamaLlmProvider(base_url="http://ollama.test", model="test-model")


def _nvidia() -> NvidiaLlmProvider:
    return NvidiaLlmProvider(
        base_url="https://nvidia.test/v1", model="test/model", api_key="secret-key"
    )


def test_scripted_provider_returns_responses_in_order() -> None:
    provider = ScriptedLlmProvider(["one", "two"])

    assert provider.generate(_REQUEST).text == "one"
    assert provider.generate(_REQUEST).text == "two"
    assert len(provider.calls) == 2


def test_scripted_provider_raises_when_exhausted() -> None:
    with pytest.raises(ProviderError):
        ScriptedLlmProvider([]).generate(_REQUEST)


def test_scripted_provider_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENTS_FAKE_LLM_RESPONSES", '["a", "b"]')
    provider = ScriptedLlmProvider.from_env()
    assert provider.generate(_REQUEST).text == "a"


def test_ollama_adapter_extracts_message_content(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, Any] = {}

    def fake_post(url: str, **kwargs: Any) -> httpx.Response:
        captured["url"] = url
        captured["json"] = kwargs["json"]
        return httpx.Response(
            200,
            json={"message": {"content": '{"echoed": "x"}'}},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx, "post", fake_post)

    result = _ollama().generate(_REQUEST)

    assert result.text == '{"echoed": "x"}'
    assert result.provider == "ollama"
    assert result.model == "test-model"
    assert captured["url"] == "http://ollama.test/api/chat"
    assert captured["json"]["format"] == "json"
    assert captured["json"]["messages"][0]["role"] == "system"


def test_ollama_adapter_maps_timeout_to_retryable_timed_out(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def boom(url: str, **kwargs: Any) -> httpx.Response:
        raise httpx.ConnectTimeout("timed out")

    monkeypatch.setattr(httpx, "post", boom)

    with pytest.raises(ProviderError) as caught:
        _ollama().generate(_REQUEST)

    assert caught.value.retryable is True
    assert caught.value.timed_out is True


def test_ollama_adapter_maps_connection_error_to_retryable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def boom(url: str, **kwargs: Any) -> httpx.Response:
        raise httpx.ConnectError("refused")

    monkeypatch.setattr(httpx, "post", boom)

    with pytest.raises(ProviderError) as caught:
        _ollama().generate(_REQUEST)

    assert caught.value.retryable is True
    assert caught.value.timed_out is False


def test_ollama_adapter_maps_http_error_to_retryable(monkeypatch: pytest.MonkeyPatch) -> None:
    def server_error(url: str, **kwargs: Any) -> httpx.Response:
        return httpx.Response(500, text="boom", request=httpx.Request("POST", url))

    monkeypatch.setattr(httpx, "post", server_error)

    with pytest.raises(ProviderError) as caught:
        _ollama().generate(_REQUEST)

    assert caught.value.retryable is True


def test_nvidia_adapter_requires_an_api_key() -> None:
    with pytest.raises(ValueError, match="NVIDIA_API_KEY"):
        NvidiaLlmProvider(base_url="https://nvidia.test/v1", model="m", api_key="")


def test_nvidia_adapter_calls_openai_compatible_endpoint(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, Any] = {}

    def fake_post(url: str, **kwargs: Any) -> httpx.Response:
        captured["url"] = url
        captured["json"] = kwargs["json"]
        captured["headers"] = kwargs["headers"]
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "[2]"}}]},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx, "post", fake_post)

    result = _nvidia().generate(_TEXT_REQUEST)

    assert result.text == "[2]"
    assert result.provider == "nvidia"
    assert captured["url"] == "https://nvidia.test/v1/chat/completions"
    assert captured["headers"]["Authorization"] == "Bearer secret-key"
    assert "response_format" not in captured["json"]  # json_output is False


def test_nvidia_adapter_requests_json_object_when_json_output(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, Any] = {}

    def fake_post(url: str, **kwargs: Any) -> httpx.Response:
        captured["json"] = kwargs["json"]
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "{}"}}]},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx, "post", fake_post)
    _nvidia().generate(_REQUEST)

    assert captured["json"]["response_format"] == {"type": "json_object"}


def test_nvidia_adapter_disables_thinking_when_json_output(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, Any] = {}

    def fake_post(url: str, **kwargs: Any) -> httpx.Response:
        captured["json"] = kwargs["json"]
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "{}"}}]},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx, "post", fake_post)
    _nvidia().generate(_REQUEST)

    # Confirmed live against nemotron-3-super-120b-a12b: without this, the model can
    # finish (finish_reason=stop) having written the correct answer to reasoning_content
    # but left `content` as a degenerate stub (`{}` / `{"":""}`) — the traced root cause
    # of AI_OUTPUT_INVALID failures on importance/summarize. Vendor-documented toggle.
    assert captured["json"]["chat_template_kwargs"] == {"enable_thinking": False}


def test_nvidia_adapter_does_not_disable_thinking_for_free_text_generation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, Any] = {}

    def fake_post(url: str, **kwargs: Any) -> httpx.Response:
        captured["json"] = kwargs["json"]
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "[2]"}}]},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx, "post", fake_post)
    _nvidia().generate(_TEXT_REQUEST)

    # Scoped to json_output only — semantic-chunk-boundary's plain-text generation keeps
    # its default reasoning behaviour, since the bug is specific to the json_object
    # grammar hand-off, not shown to affect free-text generation.
    assert "chat_template_kwargs" not in captured["json"]


def test_nvidia_adapter_maps_timeout_to_retryable_timed_out(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def boom(url: str, **kwargs: Any) -> httpx.Response:
        raise httpx.ReadTimeout("timed out")

    monkeypatch.setattr(httpx, "post", boom)

    with pytest.raises(ProviderError) as caught:
        _nvidia().generate(_REQUEST)

    assert caught.value.retryable is True
    assert caught.value.timed_out is True


def test_nvidia_adapter_passes_explicit_httpx_timeout_components(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Every phase gets the same configured ceiling, named explicitly — not a bare float
    (docs/adr timeout-hang fix)."""
    captured: dict[str, Any] = {}

    def fake_post(url: str, **kwargs: Any) -> httpx.Response:
        captured["timeout"] = kwargs["timeout"]
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "ok"}}]},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx, "post", fake_post)

    _nvidia().generate(GenerationRequest(system="s", user="u", timeout_seconds=7.5))

    timeout = captured["timeout"]
    assert isinstance(timeout, httpx.Timeout)
    assert timeout.connect == 7.5
    assert timeout.read == 7.5
    assert timeout.write == 7.5
    assert timeout.pool == 7.5


def test_nvidia_adapter_timeout_message_reports_actual_elapsed_time(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The old message ('did not respond within 240.0s') reads as if the request took exactly
    the configured ceiling — it took ~11 hours in the incident this guards against. The message
    must carry the real measured wait, not just the configured value, plus which model was
    called."""

    def slow_timeout(url: str, **kwargs: Any) -> httpx.Response:
        time.sleep(0.05)
        raise httpx.ReadTimeout("timed out")

    monkeypatch.setattr(httpx, "post", slow_timeout)

    with pytest.raises(ProviderError) as caught:
        NvidiaLlmProvider(
            base_url="https://nvidia.test/v1", model="vendor/some-model", api_key="secret-key"
        ).generate(GenerationRequest(system="s", user="u", timeout_seconds=1.0))

    message = caught.value.message
    assert "vendor/some-model" in message
    assert "1.0s" in message  # configured ceiling
    match = re.search(r"actual wait ([0-9.]+)s", message)
    assert match, message
    actual_wait = float(match.group(1))
    assert actual_wait >= 0.05
    assert actual_wait < 1.0  # measured, not the configured ceiling restated


def test_nvidia_adapter_maps_http_error_to_retryable(monkeypatch: pytest.MonkeyPatch) -> None:
    def unauthorized(url: str, **kwargs: Any) -> httpx.Response:
        return httpx.Response(401, text="nope", request=httpx.Request("POST", url))

    monkeypatch.setattr(httpx, "post", unauthorized)

    with pytest.raises(ProviderError) as caught:
        _nvidia().generate(_REQUEST)

    assert caught.value.retryable is True


# --- Ollama embedding adapter -----------------------------------------------


def _embed_reply(url: str, count: int = 1, dim: int = 3, **kwargs: Any) -> httpx.Response:
    return httpx.Response(
        200,
        json={"embeddings": [[0.1 * (i + 1)] * dim for i in range(count)]},
        request=httpx.Request("POST", url),
    )


def test_ollama_embed_calls_the_batch_endpoint_without_a_prefix_for_bge_m3(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, Any] = {}

    def fake_post(url: str, **kwargs: Any) -> httpx.Response:
        captured["url"] = url
        captured["json"] = kwargs["json"]
        return _embed_reply(url, count=2)

    monkeypatch.setattr(httpx, "post", fake_post)
    provider = OllamaEmbeddingProvider(base_url="http://ollama.test", model="bge-m3")

    result = provider.embed(EmbeddingRequest(texts=["a", "b"], role="query", timeout_seconds=1.0))

    assert captured["url"] == "http://ollama.test/api/embed"
    assert captured["json"]["input"] == ["a", "b"]  # bge-m3 is symmetric — no prefix
    assert result.dimension == 3
    assert len(result.vectors) == 2
    assert result.provider == "ollama"


def test_ollama_embed_applies_asymmetric_prefixes_for_nomic(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: list[dict[str, Any]] = []

    def fake_post(url: str, **kwargs: Any) -> httpx.Response:
        captured.append(kwargs["json"])
        return _embed_reply(url, count=1)

    monkeypatch.setattr(httpx, "post", fake_post)
    provider = OllamaEmbeddingProvider(base_url="http://ollama.test", model="nomic-embed-text")

    provider.embed(EmbeddingRequest(texts=["q"], role="query", timeout_seconds=1.0))
    provider.embed(EmbeddingRequest(texts=["p"], role="passage", timeout_seconds=1.0))

    assert captured[0]["input"] == ["search_query: q"]
    assert captured[1]["input"] == ["search_document: p"]


def test_ollama_embed_returns_empty_without_calling_for_no_texts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def boom(url: str, **kwargs: Any) -> httpx.Response:
        raise AssertionError("must not call the model for an empty request")

    monkeypatch.setattr(httpx, "post", boom)
    provider = OllamaEmbeddingProvider(base_url="http://ollama.test", model="bge-m3")

    result = provider.embed(EmbeddingRequest(texts=[], role="passage", timeout_seconds=1.0))

    assert result.vectors == []
    assert result.dimension == 0


def test_ollama_embed_rejects_a_count_mismatch(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(httpx, "post", lambda url, **kw: _embed_reply(url, count=1))
    provider = OllamaEmbeddingProvider(base_url="http://ollama.test", model="bge-m3")

    with pytest.raises(ProviderError):
        provider.embed(EmbeddingRequest(texts=["a", "b"], role="query", timeout_seconds=1.0))


def test_ollama_embed_maps_timeout(monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(url: str, **kwargs: Any) -> httpx.Response:
        raise httpx.ReadTimeout("slow")

    monkeypatch.setattr(httpx, "post", boom)
    provider = OllamaEmbeddingProvider(base_url="http://ollama.test", model="bge-m3")

    with pytest.raises(ProviderError) as caught:
        provider.embed(EmbeddingRequest(texts=["a"], role="query", timeout_seconds=1.0))
    assert caught.value.timed_out is True
