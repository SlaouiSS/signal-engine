"""``embed`` — text(s) -> vector(s) (docs/06-ai-agents.md Section 4.6;
docs/03-technical-spec.md Section 7.2; docs/adr/0011-embedding-contract-and-local-model.md).

Given one or more texts and their role (``query`` or ``passage``), return one
vector per text plus the dimension and the model id. It has no prompt: it calls
the configured :class:`~app.providers.base.EmbeddingProvider` directly, then
checks the vectors are well-formed (right count, one consistent dimension, all
finite) before returning them — a malformed embedding is ``AI_OUTPUT_INVALID``,
never silently reshaped.
"""

from __future__ import annotations

import math
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.capabilities.base import HandledResult
from app.errors import OutputInvalid, ProviderTimeout, ProviderUnavailable, RequestInvalid
from app.providers.base import EmbeddingProvider, EmbeddingRequest, ProviderError

_CAPABILITY_NAME = "embed"
_CONTRACT_VERSION = 1
_MAX_TEXTS = 256
_MAX_TEXT_CHARS = 40_000


class EmbedPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    texts: list[str] = Field(min_length=1, max_length=_MAX_TEXTS)
    role: str = Field(pattern="^(query|passage)$")

    def validated_texts(self) -> list[str]:
        for index, text in enumerate(self.texts):
            if not text.strip():
                raise ValueError(f"text at index {index} is blank")
            if len(text) > _MAX_TEXT_CHARS:
                raise ValueError(f"text at index {index} exceeds {_MAX_TEXT_CHARS} characters")
        return self.texts


class EmbedResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    vectors: list[list[float]]
    dimension: int = Field(gt=0)
    model: str


class EmbedCapability:
    name = _CAPABILITY_NAME
    contract_version = _CONTRACT_VERSION

    def __init__(self, *, provider: EmbeddingProvider, timeout_seconds: float) -> None:
        self._provider = provider
        self._timeout_seconds = timeout_seconds

    def handle(self, payload: dict[str, Any], correlation_id: str) -> HandledResult:
        try:
            request = EmbedPayload.model_validate(payload)
            texts = request.validated_texts()
        except (ValidationError, ValueError) as invalid:
            raise RequestInvalid(
                "embed payload is invalid", details={"error": str(invalid)}
            ) from invalid

        try:
            embedding = self._provider.embed(
                EmbeddingRequest(
                    texts=texts,
                    role="query" if request.role == "query" else "passage",
                    timeout_seconds=self._timeout_seconds,
                )
            )
        except ProviderError as provider_error:
            if provider_error.timed_out:
                raise ProviderTimeout(provider_error.message) from provider_error
            raise ProviderUnavailable(provider_error.message) from provider_error

        _check_well_formed(embedding.vectors, expected_count=len(texts))

        return HandledResult(
            result={
                "vectors": embedding.vectors,
                "dimension": embedding.dimension,
                "model": embedding.model,
            },
            prompt_version=f"{_CAPABILITY_NAME}/v{_CONTRACT_VERSION}",
            provider=embedding.provider,
            model=embedding.model,
            duration_millis=embedding.duration_millis,
        )


def _check_well_formed(vectors: list[list[float]], *, expected_count: int) -> None:
    if len(vectors) != expected_count:
        raise OutputInvalid(
            f"the embedding model returned {len(vectors)} vectors for {expected_count} texts"
        )
    if not vectors[0]:
        raise OutputInvalid("the embedding model returned an empty vector")
    dimensions = {len(vector) for vector in vectors}
    if len(dimensions) != 1:
        raise OutputInvalid(
            f"the embedding model returned inconsistent dimensions {sorted(dimensions)}"
        )
    for index, vector in enumerate(vectors):
        for component in vector:
            if not math.isfinite(component):
                raise OutputInvalid(f"vector {index} contains a non-finite value")
