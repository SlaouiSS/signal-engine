"""The summarize capability with a deterministic scripted provider (no LLM).

Source-grounding is enforced by the prompt and by structural validation; these
tests exercise the contract, the repair path, and the length guard.
"""

from __future__ import annotations

import json

import pytest

from app.capabilities.summarize import SummarizeCapability
from app.errors import OutputInvalid, RequestInvalid
from app.prompts import PromptLibrary
from app.providers.scripted import ScriptedLlmProvider

_PAYLOAD = {
    "content": (
        "Acme Corp said on Monday it will acquire Beta Ltd for 1.2 billion euros. "
        "The deal is expected to close in Q3, subject to regulatory approval."
    ),
    "sources": [{"name": "Example Newswire", "url": "https://ex.test/acme-beta"}],
}


def _capability(prompts: PromptLibrary, responses: list[str]) -> SummarizeCapability:
    return SummarizeCapability(
        provider=ScriptedLlmProvider(responses), prompts=prompts, timeout_seconds=5.0
    )


def _summary(*, summary: str, notes: str = "based on the provided content") -> str:
    return json.dumps({"summary": summary, "groundingNotes": notes})


def test_valid_summary(prompt_library: PromptLibrary) -> None:
    output = _summary(summary="Acme Corp will buy Beta Ltd for 1.2bn euros, closing in Q3.")
    handled = _capability(prompt_library, [output]).handle(_PAYLOAD, "c")

    assert "Acme" in handled.result["summary"]
    assert handled.result["groundingNotes"]
    assert handled.prompt_version == "summarize/v1"


def test_invalid_payload_rejected_before_provider(prompt_library: PromptLibrary) -> None:
    provider = ScriptedLlmProvider([])
    capability = SummarizeCapability(provider=provider, prompts=prompt_library, timeout_seconds=5.0)
    with pytest.raises(RequestInvalid):
        capability.handle({"sources": []}, "c")  # content missing
    assert provider.calls == []


def test_missing_grounding_notes_is_rejected_after_repair(prompt_library: PromptLibrary) -> None:
    bad = json.dumps({"summary": "something", "groundingNotes": ""})
    with pytest.raises(OutputInvalid):
        _capability(prompt_library, [bad, bad]).handle(_PAYLOAD, "c")


def test_one_repair_recovers_missing_grounding_notes(prompt_library: PromptLibrary) -> None:
    bad = json.dumps({"summary": "Acme buys Beta."})  # groundingNotes missing
    good = _summary(summary="Acme buys Beta Ltd for 1.2bn euros.")
    handled = _capability(prompt_library, [bad, good]).handle(_PAYLOAD, "c")
    assert handled.result["groundingNotes"]


def test_runaway_length_is_rejected(prompt_library: PromptLibrary) -> None:
    huge = _summary(summary="x " * 2000)
    with pytest.raises(OutputInvalid):
        _capability(prompt_library, [huge, huge]).handle(_PAYLOAD, "c")


def test_provider_independence(prompt_library: PromptLibrary) -> None:
    provider = ScriptedLlmProvider([_summary(summary="Acme buys Beta.")])
    handled = SummarizeCapability(
        provider=provider, prompts=prompt_library, timeout_seconds=5.0
    ).handle(_PAYLOAD, "c")
    assert handled.provider == "scripted"
    assert len(provider.calls) == 1
