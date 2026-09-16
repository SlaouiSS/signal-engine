"""``importance`` — is this *relevant* information important enough to bring to the
user's attention as a Signal?

Answers the second product question, kept strictly separate from relevance
(`docs/06-ai-agents.md` Section 4.4; `docs/02-functional-spec.md` Section 7.6). It
returns a bounded structured judgement: important-enough (bool) plus a short
reason. It does NOT define or apply a score, threshold, ranking, or
forecasting/opportunity logic, and it does NOT create the Signal or set its state —
that deterministic transition is Java's (Section 6). The exact signal-selection
criteria remain an open product question (Q4 / T16); this capability only supplies
the assessment.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.capabilities.base import HandledResult
from app.capabilities.structured import generate_structured
from app.errors import RequestInvalid
from app.prompts import PromptLibrary
from app.providers.base import LlmProvider

_CAPABILITY_NAME = "importance"
_CONTRACT_VERSION = 1
_PROMPT_VERSION = 1
_MAX_TEXT_CHARS = 20_000


class _Item(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=_MAX_TEXT_CHARS)


class _RelevanceContext(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    reason: str = Field(min_length=1, max_length=600)
    area_codes: list[str] = Field(alias="areaCodes", default_factory=list, max_length=6)


class ImportancePayload(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    item: _Item
    relevance_context: _RelevanceContext = Field(alias="relevanceContext")


class ImportanceResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    important_enough: bool = Field(alias="importantEnough")
    reason: str = Field(max_length=600)


class ImportanceCapability:
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
            request = ImportancePayload.model_validate(payload)
        except ValidationError as invalid:
            raise RequestInvalid(
                "importance payload is invalid",
                details={"errors": invalid.errors(include_url=False)},
            ) from invalid

        prompt = self._prompts.load(_CAPABILITY_NAME, _PROMPT_VERSION)
        rendered = prompt.render(
            item=request.item.text,
            relevance_reason=request.relevance_context.reason,
            areas=", ".join(request.relevance_context.area_codes) or "(none)",
        )
        result, generation = generate_structured(
            provider=self._provider,
            prompt=rendered,
            repair_prompt=self._prompts.load("repair", 1),
            result_model=ImportanceResult,
            timeout_seconds=self._timeout_seconds,
            extra_check=_check,
        )
        return HandledResult(
            result=result.model_dump(by_alias=True),
            prompt_version=prompt.qualified_version,
            provider=generation.provider,
            model=generation.model,
            duration_millis=generation.duration_millis,
        )


def _check(result: ImportanceResult) -> None:
    if result.important_enough and not result.reason.strip():
        raise ValueError("an important-enough judgement must have a reason")
