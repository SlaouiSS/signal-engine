"""The importance capability with a deterministic scripted provider (no LLM)."""

from __future__ import annotations

import json

import pytest

from app.capabilities.importance import ImportanceCapability
from app.errors import OutputInvalid, RequestInvalid
from app.prompts import PromptLibrary
from app.providers.scripted import ScriptedLlmProvider

_PAYLOAD = {
    "item": {"text": "A central bank raised its benchmark rate by 50 basis points."},
    "relevanceContext": {
        "reason": "monetary policy change",
        "areaCodes": ["MARKETS_AND_INVESTMENT"],
    },
}


def _capability(prompts: PromptLibrary, responses: list[str]) -> ImportanceCapability:
    return ImportanceCapability(
        provider=ScriptedLlmProvider(responses), prompts=prompts, timeout_seconds=5.0
    )


def _verdict(*, important: bool, reason: str = "r") -> str:
    return json.dumps({"importantEnough": important, "reason": reason})


def test_important_verdict(prompt_library: PromptLibrary) -> None:
    handled = _capability(
        prompt_library, [_verdict(important=True, reason="major rate move")]
    ).handle(_PAYLOAD, "c")
    assert handled.result["importantEnough"] is True
    assert handled.prompt_version == "importance/v1"


def test_not_important_verdict(prompt_library: PromptLibrary) -> None:
    handled = _capability(prompt_library, [_verdict(important=False)]).handle(_PAYLOAD, "c")
    assert handled.result["importantEnough"] is False


def test_invalid_payload_rejected_before_provider(prompt_library: PromptLibrary) -> None:
    provider = ScriptedLlmProvider([])
    capability = ImportanceCapability(
        provider=provider, prompts=prompt_library, timeout_seconds=5.0
    )
    with pytest.raises(RequestInvalid):
        capability.handle({"item": {"text": "x"}}, "c")  # relevanceContext missing
    assert provider.calls == []


def test_important_without_reason_is_rejected_after_repair(prompt_library: PromptLibrary) -> None:
    bad = _verdict(important=True, reason="   ")
    with pytest.raises(OutputInvalid):
        _capability(prompt_library, [bad, bad]).handle(_PAYLOAD, "c")


def test_missing_reason_repaired_once(prompt_library: PromptLibrary) -> None:
    handled = _capability(
        prompt_library, [_verdict(important=True, reason=""), _verdict(important=True, reason="ok")]
    ).handle(_PAYLOAD, "c")
    assert handled.result["reason"] == "ok"


def test_malformed_json_triggers_one_repair(prompt_library: PromptLibrary) -> None:
    handled = _capability(prompt_library, ["{", _verdict(important=False)]).handle(_PAYLOAD, "c")
    assert handled.result["importantEnough"] is False


def test_provider_independence(prompt_library: PromptLibrary) -> None:
    provider = ScriptedLlmProvider([_verdict(important=False)])
    handled = ImportanceCapability(
        provider=provider, prompts=prompt_library, timeout_seconds=5.0
    ).handle(_PAYLOAD, "c")
    assert handled.provider == "scripted"
    assert len(provider.calls) == 1
