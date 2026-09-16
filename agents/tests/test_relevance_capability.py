"""The relevance capability with a deterministic scripted provider (no LLM)."""

from __future__ import annotations

import json

import pytest

from app.capabilities.relevance import RelevanceCapability
from app.errors import OutputInvalid, RequestInvalid
from app.prompts import PromptLibrary
from app.providers.scripted import ScriptedLlmProvider

_PAYLOAD = {
    "item": {"text": "The EU formally adopted the AI Act today."},
    "areas": [
        {"code": "LAW_AND_REGULATION", "name": "Law & Regulation"},
        {"code": "AI_AND_TECHNOLOGY", "name": "AI & Technology"},
    ],
    "interests": [
        {"id": "i-1", "areaCode": "LAW_AND_REGULATION", "description": "EU AI regulation"}
    ],
}


def _capability(prompts: PromptLibrary, responses: list[str]) -> RelevanceCapability:
    return RelevanceCapability(
        provider=ScriptedLlmProvider(responses), prompts=prompts, timeout_seconds=5.0
    )


def _verdict(*, relevant: bool, areas: list[str], interests: list[str], reason: str = "r") -> str:
    return json.dumps(
        {
            "relevant": relevant,
            "reason": reason,
            "matchedAreaCodes": areas,
            "matchedInterestIds": interests,
        }
    )


def test_valid_relevant_verdict(prompt_library: PromptLibrary) -> None:
    output = _verdict(
        relevant=True, areas=["LAW_AND_REGULATION"], interests=["i-1"], reason="EU AI Act"
    )
    handled = _capability(prompt_library, [output]).handle(_PAYLOAD, "c")

    assert handled.result["relevant"] is True
    assert handled.result["matchedAreaCodes"] == ["LAW_AND_REGULATION"]
    assert handled.prompt_version == "relevance/v1"


def test_not_relevant_verdict(prompt_library: PromptLibrary) -> None:
    handled = _capability(
        prompt_library, [_verdict(relevant=False, areas=[], interests=[])]
    ).handle(_PAYLOAD, "c")
    assert handled.result["relevant"] is False


def test_invalid_payload_rejected_before_provider(prompt_library: PromptLibrary) -> None:
    provider = ScriptedLlmProvider([])
    capability = RelevanceCapability(provider=provider, prompts=prompt_library, timeout_seconds=5.0)
    with pytest.raises(RequestInvalid):
        capability.handle({"item": {"text": "x"}, "areas": []}, "c")  # areas must be non-empty
    assert provider.calls == []


def test_matched_area_not_in_catalog_triggers_repair(prompt_library: PromptLibrary) -> None:
    bad = _verdict(relevant=True, areas=["NOT_A_REAL_AREA"], interests=[])
    good = _verdict(relevant=True, areas=["AI_AND_TECHNOLOGY"], interests=[])
    handled = _capability(prompt_library, [bad, good]).handle(_PAYLOAD, "c")
    assert handled.result["matchedAreaCodes"] == ["AI_AND_TECHNOLOGY"]


def test_relevant_without_a_matched_area_is_rejected_after_repair(
    prompt_library: PromptLibrary,
) -> None:
    bad = _verdict(relevant=True, areas=[], interests=[])
    with pytest.raises(OutputInvalid):
        _capability(prompt_library, [bad, bad]).handle(_PAYLOAD, "c")


def test_malformed_json_triggers_one_repair(prompt_library: PromptLibrary) -> None:
    handled = _capability(
        prompt_library, ["not json", _verdict(relevant=False, areas=[], interests=[])]
    ).handle(_PAYLOAD, "c")
    assert handled.result["relevant"] is False


def test_provider_independence(prompt_library: PromptLibrary) -> None:
    provider = ScriptedLlmProvider([_verdict(relevant=False, areas=[], interests=[])])
    handled = RelevanceCapability(
        provider=provider, prompts=prompt_library, timeout_seconds=5.0
    ).handle(_PAYLOAD, "c")
    assert handled.provider == "scripted"
    assert len(provider.calls) == 1
