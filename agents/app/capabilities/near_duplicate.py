"""``near-duplicate`` — the first Signal Engine AI capability.

Given one **candidate** text and a small, Java-chosen set of **comparison** texts,
it decides, for each comparison, whether the candidate reports the *same
underlying story / event / fact* (not merely the same topic). It returns one
structured verdict per comparison and nothing else — it does not rank, score,
group, or decide what to persist. Java owns the candidate set, the final grouping
decision, and all persistence (docs/06-ai-agents.md Section 4.1, 6;
docs/adr/0007-semantic-deduplication-relevant-information.md).
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from app.capabilities.base import HandledResult
from app.capabilities.structured import generate_structured
from app.errors import RequestInvalid
from app.prompts import PromptLibrary
from app.providers.base import LlmProvider

_CAPABILITY_NAME = "near-duplicate"
_CONTRACT_VERSION = 1
_PROMPT_VERSION = 1
_MAX_COMPARISONS = 25
_MAX_TEXT_CHARS = 20_000


class _CandidateText(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=_MAX_TEXT_CHARS)


class _Comparison(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=1, max_length=200)
    text: str = Field(min_length=1, max_length=_MAX_TEXT_CHARS)


class NearDuplicatePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    candidate: _CandidateText
    comparisons: list[_Comparison] = Field(min_length=1, max_length=_MAX_COMPARISONS)

    @model_validator(mode="after")
    def _comparison_ids_are_unique(self) -> NearDuplicatePayload:
        ids = [comparison.id for comparison in self.comparisons]
        if len(ids) != len(set(ids)):
            raise ValueError("comparison ids must be unique")
        return self


class _Assessment(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    id: str
    same_underlying_story: bool = Field(alias="sameUnderlyingStory")
    reason: str = Field(max_length=600)


class NearDuplicateResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    assessments: list[_Assessment]


class NearDuplicateCapability:
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
            request = NearDuplicatePayload.model_validate(payload)
        except ValidationError as invalid:
            raise RequestInvalid(
                "near-duplicate payload is invalid",
                details={"errors": invalid.errors(include_url=False)},
            ) from invalid

        prompt = self._prompts.load(_CAPABILITY_NAME, _PROMPT_VERSION)
        rendered = prompt.render(
            candidate=request.candidate.text,
            comparisons=_format_comparisons(request.comparisons),
        )
        expected_ids = {comparison.id for comparison in request.comparisons}
        result, generation = generate_structured(
            provider=self._provider,
            prompt=rendered,
            repair_prompt=self._prompts.load("repair", 1),
            result_model=NearDuplicateResult,
            timeout_seconds=self._timeout_seconds,
            extra_check=lambda candidate_result: _require_one_verdict_per_comparison(
                expected_ids, candidate_result
            ),
        )
        return HandledResult(
            result=result.model_dump(by_alias=True),
            prompt_version=prompt.qualified_version,
            provider=generation.provider,
            model=generation.model,
            duration_millis=generation.duration_millis,
        )


def _format_comparisons(comparisons: list[_Comparison]) -> str:
    return "\n\n".join(
        f'--- COMPARISON id="{comparison.id}" ---\n{comparison.text}' for comparison in comparisons
    )


def _require_one_verdict_per_comparison(
    expected_ids: set[str], result: NearDuplicateResult
) -> None:
    returned_ids = [assessment.id for assessment in result.assessments]
    if set(returned_ids) != expected_ids or len(returned_ids) != len(expected_ids):
        raise ValueError(
            "expected exactly one verdict per comparison id "
            f"{sorted(expected_ids)}, got {sorted(returned_ids)}"
        )
