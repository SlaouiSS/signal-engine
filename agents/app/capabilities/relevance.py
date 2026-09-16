"""``relevance`` — is this information relevant to the user's configured
interests and areas?

Answers the first of the two product questions (`docs/06-ai-agents.md` Section 4.3;
`docs/02-functional-spec.md` Section 7.5). It matches the item against the six
fixed areas and the user's optional interests and returns a bounded structured
verdict: relevant (bool), a short reason, and which areas/interests it matched. It
does NOT decide importance, decide whether a Signal is created, or apply a numeric
score — those are separate concerns owned by Java (Section 6).
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.capabilities.base import HandledResult
from app.capabilities.structured import generate_structured
from app.errors import RequestInvalid
from app.prompts import PromptLibrary
from app.providers.base import LlmProvider

_CAPABILITY_NAME = "relevance"
_CONTRACT_VERSION = 1
_PROMPT_VERSION = 1
_MAX_TEXT_CHARS = 20_000


class _Item(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=_MAX_TEXT_CHARS)


class _Area(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    code: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=200)


class _Interest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    id: str = Field(min_length=1, max_length=200)
    area_code: str = Field(alias="areaCode", min_length=1, max_length=100)
    description: str = Field(min_length=1, max_length=2_000)


class RelevancePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    item: _Item
    areas: list[_Area] = Field(min_length=1, max_length=6)
    interests: list[_Interest] = Field(default_factory=list, max_length=200)


class RelevanceResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    relevant: bool
    reason: str = Field(max_length=600)
    matched_area_codes: list[str] = Field(alias="matchedAreaCodes", default_factory=list)
    matched_interest_ids: list[str] = Field(alias="matchedInterestIds", default_factory=list)


class RelevanceCapability:
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
            request = RelevancePayload.model_validate(payload)
        except ValidationError as invalid:
            raise RequestInvalid(
                "relevance payload is invalid",
                details={"errors": invalid.errors(include_url=False)},
            ) from invalid

        known_area_codes = {area.code for area in request.areas}
        known_interest_ids = {interest.id for interest in request.interests}

        prompt = self._prompts.load(_CAPABILITY_NAME, _PROMPT_VERSION)
        rendered = prompt.render(
            item=request.item.text,
            areas=_format_areas(request.areas),
            interests=_format_interests(request.interests),
        )
        result, generation = generate_structured(
            provider=self._provider,
            prompt=rendered,
            repair_prompt=self._prompts.load("repair", 1),
            result_model=RelevanceResult,
            timeout_seconds=self._timeout_seconds,
            extra_check=lambda candidate: _check(candidate, known_area_codes, known_interest_ids),
        )
        return HandledResult(
            result=result.model_dump(by_alias=True),
            prompt_version=prompt.qualified_version,
            provider=generation.provider,
            model=generation.model,
            duration_millis=generation.duration_millis,
        )


def _format_areas(areas: list[_Area]) -> str:
    return "\n".join(f'- code="{area.code}": {area.name}' for area in areas)


def _format_interests(interests: list[_Interest]) -> str:
    if not interests:
        return "(none configured)"
    return "\n".join(
        f'- id="{interest.id}" (area {interest.area_code}): {interest.description}'
        for interest in interests
    )


def _check(
    result: RelevanceResult, known_area_codes: set[str], known_interest_ids: set[str]
) -> None:
    unknown_areas = set(result.matched_area_codes) - known_area_codes
    if unknown_areas:
        raise ValueError(f"matchedAreaCodes not in the supplied catalog: {sorted(unknown_areas)}")
    unknown_interests = set(result.matched_interest_ids) - known_interest_ids
    if unknown_interests:
        raise ValueError(f"matchedInterestIds not supplied: {sorted(unknown_interests)}")
    if result.relevant and not result.matched_area_codes:
        raise ValueError("a relevant item must match at least one area")
    if result.relevant and not result.reason.strip():
        raise ValueError("a relevant item must have a reason")
