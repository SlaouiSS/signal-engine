"""The provider-independent LLM port used by every capability.

``LlmProvider`` is a :class:`typing.Protocol`, not a base class — an adapter just
needs a matching ``generate``. Capabilities never import a concrete provider
(docs/03-technical-spec.md Section 9.2; docs/06-ai-agents.md Section 9).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal, Protocol


@dataclass(frozen=True, slots=True)
class GenerationRequest:
    """One structured-output generation call."""

    system: str
    user: str
    # When True the adapter asks the runtime for JSON-constrained output
    # (docs/03-technical-spec.md Section 9.4, 9.6).
    json_output: bool = True
    temperature: float = 0.0
    timeout_seconds: float = 60.0
    # Free-form, adapter-specific hints (kept for forward compatibility; the
    # Ollama adapter ignores unknown keys).
    options: dict[str, float] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class GenerationResult:
    """The raw model output plus provenance for the response envelope."""

    text: str
    provider: str
    model: str
    duration_millis: int


class ProviderError(Exception):
    """The provider could not produce a result. ``retryable`` follows the
    transport/timeout taxonomy (docs/03-technical-spec.md Section 13.1-13.2)."""

    def __init__(self, message: str, *, retryable: bool, timed_out: bool = False) -> None:
        super().__init__(message)
        self.message = message
        self.retryable = retryable
        self.timed_out = timed_out


class LlmProvider(Protocol):
    """Chat/generate with structured-output support (docs/03-technical-spec.md
    Section 9.2)."""

    @property
    def name(self) -> str: ...

    @property
    def model(self) -> str: ...

    def generate(self, request: GenerationRequest) -> GenerationResult: ...


TextRole = Literal["query", "passage"]


@dataclass(frozen=True, slots=True)
class EmbeddingRequest:
    """One embedding call (docs/06-ai-agents.md Section 4.6). ``role`` lets an
    asymmetric model apply its documented query vs. passage handling."""

    texts: list[str]
    role: TextRole
    timeout_seconds: float = 30.0


@dataclass(frozen=True, slots=True)
class EmbeddingResult:
    """One vector per input text (same order), plus provenance for the envelope."""

    vectors: list[list[float]]
    dimension: int
    provider: str
    model: str
    duration_millis: int


class EmbeddingProvider(Protocol):
    """text(s) -> vector(s). A separate port from :class:`LlmProvider`
    (docs/03-technical-spec.md Section 9.2; docs/06-ai-agents.md Section 9)."""

    @property
    def name(self) -> str: ...

    @property
    def model(self) -> str: ...

    def embed(self, request: EmbeddingRequest) -> EmbeddingResult: ...
