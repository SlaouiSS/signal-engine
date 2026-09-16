"""``summarize`` — a concise, strictly source-grounded summary of a Signal's
underlying content.

`docs/06-ai-agents.md` Section 4.5; `docs/02-functional-spec.md` Section 8. The
capability receives only the source-grounded content and the provenance context
Java supplies — no database, no web. It returns structured fields (the summary
text and grounding notes), never free advice or claims the content does not
support. Expected length/format is an open product question (Q14); the cap here is
a provisional guard, not a resolution.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.capabilities.base import HandledResult
from app.capabilities.structured import generate_structured
from app.errors import RequestInvalid
from app.prompts import PromptLibrary
from app.providers.base import LlmProvider

_CAPABILITY_NAME = "summarize"
_CONTRACT_VERSION = 1
_PROMPT_VERSION = 1
_MAX_CONTENT_CHARS = 40_000
# Provisional summary length guard (Q14 open) — generous, only to catch runaway output.
_MAX_SUMMARY_CHARS = 1_500


class _Source(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=300)
    url: str | None = Field(default=None, max_length=2_000)


class SummarizePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    content: str = Field(min_length=1, max_length=_MAX_CONTENT_CHARS)
    sources: list[_Source] = Field(default_factory=list, max_length=25)


class SummarizeResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    summary: str = Field(min_length=1, max_length=_MAX_SUMMARY_CHARS)
    grounding_notes: str = Field(alias="groundingNotes", min_length=1, max_length=1_000)


class SummarizeCapability:
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
            request = SummarizePayload.model_validate(payload)
        except ValidationError as invalid:
            raise RequestInvalid(
                "summarize payload is invalid",
                details={"errors": invalid.errors(include_url=False)},
            ) from invalid

        prompt = self._prompts.load(_CAPABILITY_NAME, _PROMPT_VERSION)
        rendered = prompt.render(
            content=request.content,
            sources=_format_sources(request.sources),
        )
        result, generation = generate_structured(
            provider=self._provider,
            prompt=rendered,
            repair_prompt=self._prompts.load("repair", 1),
            result_model=SummarizeResult,
            timeout_seconds=self._timeout_seconds,
        )
        return HandledResult(
            result=result.model_dump(by_alias=True),
            prompt_version=prompt.qualified_version,
            provider=generation.provider,
            model=generation.model,
            duration_millis=generation.duration_millis,
        )


def _format_sources(sources: list[_Source]) -> str:
    if not sources:
        return "(no source references supplied)"
    return "\n".join(
        f"- {source.name}" + (f" <{source.url}>" if source.url else "") for source in sources
    )
