"""``semantic-chunk-boundary`` — where a document divides into coherent sections.

This capability serves the ``SlaouiSS/semantic-chunker`` Java library
(docs/adr/0010-indexing-foundation-and-semantic-chunking.md). The library composes
a self-contained boundary prompt for one window of document units and expects the
model's raw answer back — a list of unit identifiers, ``[N1,N2,N3]``, or ``[]``.
The library owns parsing, validation, and its own bounded retry, so this
capability does **not** use ``generate_structured``: it makes one plain-text
generation call and returns exactly what the model said.

It is provider-independent (only ``LlmProvider``): the same contract runs against a
strong NVIDIA Build model first and local Ollama later, chosen purely by
``AGENTS_LLM_PROVIDER``.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.capabilities.base import HandledResult
from app.errors import ProviderTimeout, ProviderUnavailable, RequestInvalid
from app.prompts import PromptLibrary
from app.providers.base import GenerationRequest, LlmProvider, ProviderError

_CAPABILITY_NAME = "semantic-chunk-boundary"
_CONTRACT_VERSION = 1
_PROMPT_VERSION = 1
_MAX_PROMPT_CHARS = 400_000


class SemanticChunkBoundaryPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    prompt: str = Field(min_length=1, max_length=_MAX_PROMPT_CHARS)
    temperature: float = Field(default=0.0, ge=0.0, le=2.0)


class SemanticChunkBoundaryResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    raw_text: str = Field(alias="rawText")


class SemanticChunkBoundaryCapability:
    name = _CAPABILITY_NAME
    contract_version = _CONTRACT_VERSION

    def __init__(
        self, *, provider: LlmProvider, prompts: PromptLibrary, timeout_seconds: float
    ) -> None:
        self._provider = provider
        self._prompts = prompts
        self._timeout_seconds = timeout_seconds

    def handle(self, payload: dict[str, Any], correlation_id: str) -> HandledResult:
        try:
            request = SemanticChunkBoundaryPayload.model_validate(payload)
        except ValidationError as invalid:
            raise RequestInvalid(
                "semantic-chunk-boundary payload is invalid",
                details={"errors": invalid.errors(include_url=False)},
            ) from invalid

        prompt = self._prompts.load(_CAPABILITY_NAME, _PROMPT_VERSION)
        rendered = prompt.render(prompt=request.prompt)

        try:
            generation = self._provider.generate(
                GenerationRequest(
                    system=rendered.system,
                    user=rendered.user,
                    json_output=False,
                    temperature=request.temperature,
                    timeout_seconds=self._timeout_seconds,
                )
            )
        except ProviderError as provider_error:
            if provider_error.timed_out:
                raise ProviderTimeout(provider_error.message) from provider_error
            raise ProviderUnavailable(provider_error.message) from provider_error

        result = SemanticChunkBoundaryResult(rawText=generation.text.strip())
        return HandledResult(
            result=result.model_dump(by_alias=True),
            prompt_version=prompt.qualified_version,
            provider=generation.provider,
            model=generation.model,
            duration_millis=generation.duration_millis,
        )
