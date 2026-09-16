"""A deterministic provider that returns pre-set responses in order.

Its purpose is testing and running the stack without a model: capability logic,
structured-output validation, and the bounded repair path are all exercised
without an LLM (docs/03-technical-spec.md Section 16.2). Tests construct it
directly with the exact responses a scenario needs; a running service builds it
from ``AGENTS_FAKE_LLM_RESPONSES`` (a JSON array of strings).
"""

from __future__ import annotations

import json
import os

from app.providers.base import GenerationRequest, GenerationResult, ProviderError

_PROVIDER_NAME = "scripted"
_MODEL = "scripted"


class ScriptedLlmProvider:
    def __init__(self, responses: list[str] | None = None) -> None:
        self._responses = list(responses or [])
        self.calls: list[GenerationRequest] = []

    @property
    def name(self) -> str:
        return _PROVIDER_NAME

    @property
    def model(self) -> str:
        return _MODEL

    def generate(self, request: GenerationRequest) -> GenerationResult:
        self.calls.append(request)
        if not self._responses:
            raise ProviderError(
                "scripted provider has no more responses configured", retryable=True
            )
        return GenerationResult(
            text=self._responses.pop(0),
            provider=_PROVIDER_NAME,
            model=_MODEL,
            duration_millis=0,
        )

    @staticmethod
    def from_env() -> ScriptedLlmProvider:
        raw = os.environ.get("AGENTS_FAKE_LLM_RESPONSES", "[]")
        parsed = json.loads(raw)
        if not isinstance(parsed, list) or not all(isinstance(item, str) for item in parsed):
            raise ValueError("AGENTS_FAKE_LLM_RESPONSES must be a JSON array of strings")
        return ScriptedLlmProvider(list(parsed))
