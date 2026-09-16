"""``answer`` — a grounded answer to a question, built only from the passages Java
retrieved.

`docs/06-ai-agents.md` Section 4.7; `docs/07-rag.md` Section 10, 26;
`docs/adr/0015-rag-grounded-generator.md`. The capability receives the question and
the context passages Java selected (each with a stable ``passageId``). It returns a
structured answer, whether that answer is grounded, and the passage ids it cites —
or an explicit ``answered = false`` when the context does not support an answer. It
does NOT retrieve, rank, or reach any store, and it never cites a passage it was
not given (checked in the bounded-repair loop). Passage text is untrusted data; the
prompt forbids following any instruction inside it.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from app.capabilities.base import HandledResult
from app.capabilities.structured import generate_structured
from app.errors import RequestInvalid
from app.prompts import PromptLibrary
from app.providers.base import LlmProvider

_CAPABILITY_NAME = "answer"
_CONTRACT_VERSION = 1
_PROMPT_VERSION = 1
_MAX_QUESTION_CHARS = 4_000
_MAX_PASSAGE_CHARS = 20_000
_MAX_PASSAGES = 200
_MAX_ANSWER_CHARS = 4_000


class _Passage(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    passage_id: str = Field(alias="passageId", min_length=1, max_length=400)
    text: str = Field(min_length=1, max_length=_MAX_PASSAGE_CHARS)
    source: str | None = Field(default=None, max_length=600)


class AnswerPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question: str = Field(min_length=1, max_length=_MAX_QUESTION_CHARS)
    passages: list[_Passage] = Field(min_length=1, max_length=_MAX_PASSAGES)

    @model_validator(mode="after")
    def _passage_ids_are_unique(self) -> AnswerPayload:
        ids = [passage.passage_id for passage in self.passages]
        if len(ids) != len(set(ids)):
            raise ValueError("passage ids must be unique")
        return self


class _Citation(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    passage_id: str = Field(alias="passageId", min_length=1, max_length=400)


class AnswerResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    answered: bool
    answer: str = Field(min_length=1, max_length=_MAX_ANSWER_CHARS)
    citations: list[_Citation] = Field(default_factory=list)


class AnswerCapability:
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
            request = AnswerPayload.model_validate(payload)
        except ValidationError as invalid:
            raise RequestInvalid(
                "answer payload is invalid",
                details={"errors": invalid.errors(include_url=False)},
            ) from invalid

        supplied_ids = {passage.passage_id for passage in request.passages}
        prompt = self._prompts.load(_CAPABILITY_NAME, _PROMPT_VERSION)
        rendered = prompt.render(
            question=request.question,
            context=_format_passages(request.passages),
        )
        result, generation = generate_structured(
            provider=self._provider,
            prompt=rendered,
            repair_prompt=self._prompts.load("repair", 1),
            result_model=AnswerResult,
            timeout_seconds=self._timeout_seconds,
            extra_check=lambda candidate: _check_grounding(candidate, supplied_ids),
        )
        return HandledResult(
            result=result.model_dump(by_alias=True),
            prompt_version=prompt.qualified_version,
            provider=generation.provider,
            model=generation.model,
            duration_millis=generation.duration_millis,
        )


def _format_passages(passages: list[_Passage]) -> str:
    blocks: list[str] = []
    for passage in passages:
        source = passage.source or "unknown"
        blocks.append(
            f'--- PASSAGE passageId="{passage.passage_id}" (source: {source}) ---\n{passage.text}'
        )
    return "\n\n".join(blocks)


def _check_grounding(result: AnswerResult, supplied_ids: set[str]) -> None:
    cited_ids = [citation.passage_id for citation in result.citations]
    unknown = set(cited_ids) - supplied_ids
    if unknown:
        raise ValueError(f"cited passage ids not in the supplied context: {sorted(unknown)}")
    if len(cited_ids) != len(set(cited_ids)):
        raise ValueError("the same passage id is cited more than once")
    if result.answered and not cited_ids:
        raise ValueError("an answered response must cite at least one passage")
    if not result.answered and cited_ids:
        raise ValueError("an insufficient-evidence response must not cite passages")
    if result.answered and not result.answer.strip():
        raise ValueError("an answered response must contain answer text")
