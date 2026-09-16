"""Ollama adapters for the project's provider ports.

Talks to Ollama over HTTP with ``httpx`` — no Ollama SDK (docs/03-technical-spec.md
Section 4.7, 23). This is the only module that knows the string ``ollama`` or the
wire shape of that runtime; capability code never does (docs/06-ai-agents.md
Section 9).

- :class:`OllamaLlmProvider` — chat/generate, ``/api/chat``.
- :class:`OllamaEmbeddingProvider` — text embeddings, ``/api/embed``
  (docs/adr/0011-embedding-contract-and-local-model.md).
"""

from __future__ import annotations

import time
from typing import Any

import httpx

from app.providers.base import (
    EmbeddingRequest,
    EmbeddingResult,
    GenerationRequest,
    GenerationResult,
    ProviderError,
)

_PROVIDER_NAME = "ollama"

# Per-model query/passage instruction prefixes for asymmetric retrieval models.
# Ollama's template for every embedding model here is a bare ``{{ .Prompt }}`` —
# no prefix is applied by the runtime — so the adapter applies the one each model
# card documents. A model absent from this table (bge-m3, granite-embedding) is
# symmetric and takes no prefix. Sources: each model's Hugging Face card.
_EMBED_PROMPTS: dict[str, tuple[str, str]] = {
    "nomic-embed-text": ("search_query: ", "search_document: "),
    "mxbai-embed-large": (
        "Represent this sentence for searching relevant passages: ",
        "",
    ),
    "snowflake-arctic-embed2": ("query: ", ""),
    "embeddinggemma": ("task: search result | query: ", "title: none | text: "),
}


def _base_model_name(model: str) -> str:
    return model.split(":", 1)[0]


def _post_json(url: str, body: dict[str, Any], timeout: float) -> httpx.Response:
    try:
        response = httpx.post(url, json=body, timeout=timeout)
        response.raise_for_status()
        return response
    except httpx.TimeoutException as timeout_error:
        raise ProviderError(
            f"ollama did not respond within {timeout}s", retryable=True, timed_out=True
        ) from timeout_error
    except httpx.HTTPStatusError as status_error:
        raise ProviderError(
            f"ollama returned HTTP {status_error.response.status_code}", retryable=True
        ) from status_error
    except httpx.HTTPError as transport_error:
        raise ProviderError(
            f"ollama is unreachable: {transport_error}", retryable=True
        ) from transport_error


class OllamaLlmProvider:
    def __init__(self, *, base_url: str, model: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._model = model

    @property
    def name(self) -> str:
        return _PROVIDER_NAME

    @property
    def model(self) -> str:
        return self._model

    def generate(self, request: GenerationRequest) -> GenerationResult:
        body: dict[str, Any] = {
            "model": self._model,
            "stream": False,
            "messages": [
                {"role": "system", "content": request.system},
                {"role": "user", "content": request.user},
            ],
            "options": {"temperature": request.temperature, **request.options},
        }
        if request.json_output:
            body["format"] = "json"

        started = time.monotonic()
        response = _post_json(f"{self._base_url}/api/chat", body, request.timeout_seconds)
        duration_millis = int((time.monotonic() - started) * 1000)
        return GenerationResult(
            text=_extract_message_content(response.json()),
            provider=_PROVIDER_NAME,
            model=self._model,
            duration_millis=duration_millis,
        )


class OllamaEmbeddingProvider:
    """``EmbeddingProvider`` over Ollama's ``/api/embed`` batch endpoint."""

    def __init__(self, *, base_url: str, model: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._query_prefix, self._passage_prefix = _EMBED_PROMPTS.get(
            _base_model_name(model), ("", "")
        )

    @property
    def name(self) -> str:
        return _PROVIDER_NAME

    @property
    def model(self) -> str:
        return self._model

    def embed(self, request: EmbeddingRequest) -> EmbeddingResult:
        if not request.texts:
            return EmbeddingResult(
                vectors=[],
                dimension=0,
                provider=_PROVIDER_NAME,
                model=self._model,
                duration_millis=0,
            )

        prefix = self._query_prefix if request.role == "query" else self._passage_prefix
        body = {"model": self._model, "input": [prefix + text for text in request.texts]}

        started = time.monotonic()
        response = _post_json(f"{self._base_url}/api/embed", body, request.timeout_seconds)
        duration_millis = int((time.monotonic() - started) * 1000)

        vectors = _extract_embeddings(response.json(), expected=len(request.texts))
        return EmbeddingResult(
            vectors=vectors,
            dimension=len(vectors[0]),
            provider=_PROVIDER_NAME,
            model=self._model,
            duration_millis=duration_millis,
        )


def _extract_message_content(payload: object) -> str:
    if not isinstance(payload, dict):
        raise ProviderError("ollama response was not a JSON object", retryable=True)
    message = payload.get("message")
    if not isinstance(message, dict) or not isinstance(message.get("content"), str):
        raise ProviderError("ollama response had no message content", retryable=True)
    return str(message["content"])


def _extract_embeddings(payload: object, *, expected: int) -> list[list[float]]:
    if not isinstance(payload, dict):
        raise ProviderError("ollama response was not a JSON object", retryable=True)
    raw = payload.get("embeddings")
    if not isinstance(raw, list) or len(raw) != expected:
        raise ProviderError(
            f"ollama returned {len(raw) if isinstance(raw, list) else 'no'} embeddings "
            f"for {expected} inputs",
            retryable=True,
        )
    vectors: list[list[float]] = []
    dimension: int | None = None
    for index, vector in enumerate(raw):
        if not isinstance(vector, list) or not vector:
            raise ProviderError(f"embedding {index} is empty", retryable=True)
        try:
            floats = [float(component) for component in vector]
        except (TypeError, ValueError) as bad:
            raise ProviderError(
                f"embedding {index} has a non-numeric component", retryable=True
            ) from bad
        if dimension is None:
            dimension = len(floats)
        elif len(floats) != dimension:
            raise ProviderError(
                "ollama returned embeddings of inconsistent dimension", retryable=True
            )
        vectors.append(floats)
    return vectors
