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


# HTTP statuses that mean the provider *configuration* is wrong — bad or missing credentials,
# exhausted account, a model or endpoint that does not exist (any more). Retrying changes nothing
# until someone fixes the configuration, and it affects every request, not one
# (docs/adr/0018-pipeline-reliability.md).
_CONFIGURATION_REJECTED_STATUSES = frozenset({401, 402, 403, 404, 410})
# Statuses that are transient even though they are 4xx (docs/03-technical-spec.md Section 13.2:
# only provider transport/5xx is retryable, plus the standard "try again later" statuses).
_TRANSIENT_CLIENT_STATUSES = frozenset({408, 425, 429})


class ProviderError(Exception):
    """The provider could not produce a result. ``retryable`` follows the
    transport/timeout taxonomy (docs/03-technical-spec.md Section 13.1-13.2).

    ``http_status`` is set when the provider answered with an HTTP error status, and is absent
    for timeouts and transport failures.
    """

    def __init__(
        self,
        message: str,
        *,
        retryable: bool,
        timed_out: bool = False,
        http_status: int | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.retryable = retryable
        self.timed_out = timed_out
        self.http_status = http_status

    @property
    def rejects_configuration(self) -> bool:
        """The provider refused because its configuration is wrong, not this one request."""
        return self.http_status in _CONFIGURATION_REJECTED_STATUSES


def http_status_error(provider_label: str, status: int) -> ProviderError:
    """The :class:`ProviderError` for a provider that answered with an HTTP error status."""
    retryable = status in _TRANSIENT_CLIENT_STATUSES or status >= 500
    return ProviderError(
        f"{provider_label} returned HTTP {status}", retryable=retryable, http_status=status
    )


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
