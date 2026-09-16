"""``echo`` — the minimal capability that proves the contract end to end.

It is deliberately trivial: it asks the provider to copy a string back and report
its length, and it validates that structured answer (schema + the self-consistent
invariant ``characterCount == len(echoed)``). It is NOT a Signal Engine AI
capability — the first real one (near-duplicate assessment) arrives in Task 6B.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from app.capabilities.base import HandledResult
from app.capabilities.structured import generate_structured
from app.errors import RequestInvalid
from app.prompts import PromptLibrary
from app.providers.base import LlmProvider

_CAPABILITY_NAME = "echo"
_CONTRACT_VERSION = 1
_PROMPT_VERSION = 1


class EchoPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=4096)
    note: str | None = Field(default=None, max_length=200)


class EchoResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    echoed: str
    character_count: int = Field(alias="characterCount", ge=0)

    @model_validator(mode="after")
    def _character_count_matches(self) -> EchoResult:
        if self.character_count != len(self.echoed):
            raise ValueError(
                f"characterCount {self.character_count} != len(echoed) {len(self.echoed)}"
            )
        return self


class EchoCapability:
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
            echo_payload = EchoPayload.model_validate(payload)
        except ValidationError as invalid:
            raise RequestInvalid(
                "echo payload is invalid", details={"errors": invalid.errors(include_url=False)}
            ) from invalid

        prompt = self._prompts.load(_CAPABILITY_NAME, _PROMPT_VERSION)
        rendered = prompt.render(text=echo_payload.text)
        result, generation = generate_structured(
            provider=self._provider,
            prompt=rendered,
            repair_prompt=self._prompts.load("repair", 1),
            result_model=EchoResult,
            timeout_seconds=self._timeout_seconds,
        )
        return HandledResult(
            result=result.model_dump(by_alias=True),
            prompt_version=prompt.qualified_version,
            provider=generation.provider,
            model=generation.model,
            duration_millis=generation.duration_millis,
        )
