"""NVIDIA Build adapter for :class:`~app.providers.base.LlmProvider`.

NVIDIA Build (``https://integrate.api.nvidia.com/v1``) exposes an OpenAI-compatible
chat-completions API, so this adapter speaks that shape over ``httpx`` — no SDK
(docs/03-technical-spec.md Section 23; docs/06-ai-agents.md Section 9). It is the
"NVIDIA Build first, Ollama later" option for development validation of the
semantic-chunking capability (docs/adr/0010-indexing-foundation-and-semantic-chunking.md).

The API key is read by this adapter from ``NVIDIA_API_KEY`` and is never logged,
committed, or placed in a URL (docs/03-technical-spec.md Section 15;
CLAUDE.md Section 13, 24).
"""

from __future__ import annotations

import time
from typing import Any

import httpx

from app.providers.base import GenerationRequest, GenerationResult, ProviderError

_PROVIDER_NAME = "nvidia"


class NvidiaLlmProvider:
    def __init__(self, *, base_url: str, model: str, api_key: str) -> None:
        if not api_key:
            raise ValueError(
                "NVIDIA_API_KEY is required when AGENTS_LLM_PROVIDER=nvidia; "
                "provision it as a secret environment variable"
            )
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._api_key = api_key

    @property
    def name(self) -> str:
        return _PROVIDER_NAME

    @property
    def model(self) -> str:
        return self._model

    def generate(self, request: GenerationRequest) -> GenerationResult:
        body: dict[str, Any] = {
            "model": self._model,
            "temperature": request.temperature,
            "messages": [
                {"role": "system", "content": request.system},
                {"role": "user", "content": request.user},
            ],
            **request.options,
        }
        if request.json_output:
            body["response_format"] = {"type": "json_object"}
            # nemotron-3-super-120b-a12b is a reasoning model: by default it writes its
            # answer into a separate `reasoning_content` field first, then is expected to
            # transcribe it into `content` under the json_object grammar. That hand-off is
            # unreliable — confirmed live against this exact model to sometimes finish
            # (finish_reason=stop, tokens to spare) having written the correct judgement to
            # reasoning_content but left content as a degenerate stub (`{}` / `{"":""}`),
            # which is what AI_OUTPUT_INVALID failures on importance/summarize traced back
            # to. Disabling thinking for JSON-mode calls removes the hand-off entirely: the
            # model answers directly in content. This is the documented, vendor-supported
            # toggle for this model family (NVIDIA NIM reference for
            # nemotron-3-super-120b-a12b, "chat_template_kwargs": {"enable_thinking": false}),
            # not a guessed parameter. Scoped to json_output only — free-text generation
            # (semantic-chunk-boundary) keeps its default reasoning behaviour unchanged.
            body["chat_template_kwargs"] = {"enable_thinking": False}

        # Explicit per-phase components rather than a bare float: a bare float applies the same
        # ceiling to connect/write/read/pool uniformly too, but naming them removes any ambiguity
        # about which phase is bounded and by how much. `httpx.post` opens a fresh, single-use
        # client for this call and closes it on return (including on this exception) — a
        # timed-out connection is torn down here, never kept around for the next request to reuse.
        timeout = httpx.Timeout(
            connect=request.timeout_seconds,
            read=request.timeout_seconds,
            write=request.timeout_seconds,
            pool=request.timeout_seconds,
        )

        started = time.monotonic()
        try:
            response = httpx.post(
                f"{self._base_url}/chat/completions",
                json=body,
                headers={"Authorization": f"Bearer {self._api_key}"},
                timeout=timeout,
            )
            response.raise_for_status()
        except httpx.TimeoutException as timeout_error:
            elapsed_seconds = time.monotonic() - started
            raise ProviderError(
                f"NVIDIA Build ({self._model}) did not respond within "
                f"{request.timeout_seconds}s (actual wait {elapsed_seconds:.1f}s)",
                retryable=True,
                timed_out=True,
            ) from timeout_error
        except httpx.HTTPStatusError as status_error:
            raise ProviderError(
                f"NVIDIA Build returned HTTP {status_error.response.status_code}",
                retryable=True,
            ) from status_error
        except httpx.HTTPError as transport_error:
            raise ProviderError(
                f"NVIDIA Build is unreachable: {transport_error}", retryable=True
            ) from transport_error

        duration_millis = int((time.monotonic() - started) * 1000)
        return GenerationResult(
            text=_extract_choice_content(response.json()),
            provider=_PROVIDER_NAME,
            model=self._model,
            duration_millis=duration_millis,
        )


def _extract_choice_content(payload: object) -> str:
    if not isinstance(payload, dict):
        raise ProviderError("NVIDIA Build response was not a JSON object", retryable=True)
    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices:
        raise ProviderError("NVIDIA Build response had no choices", retryable=True)
    message = choices[0].get("message") if isinstance(choices[0], dict) else None
    if not isinstance(message, dict) or not isinstance(message.get("content"), str):
        raise ProviderError("NVIDIA Build response had no message content", retryable=True)
    return str(message["content"])
