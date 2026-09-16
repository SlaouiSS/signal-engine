"""The near-duplicate capability: handler behaviour and the HTTP contract, all
with a deterministic scripted provider (no LLM)."""

from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

from app.capabilities.near_duplicate import NearDuplicateCapability
from app.errors import OutputInvalid, RequestInvalid
from app.main import create_app
from app.prompts import PromptLibrary
from app.providers.scripted import ScriptedLlmProvider

_CID = "8b1a9953-1f3c-4b8e-9a1d-2c4e6f8a0b2d"
_C1 = "b7c2f1a0-0000-0000-0000-000000000001"
_C2 = "b7c2f1a0-0000-0000-0000-000000000002"


def _payload() -> dict[str, object]:
    return {
        "candidate": {"text": "The central bank raised its benchmark rate by half a point today."},
        "comparisons": [
            {"id": _C1, "text": "Policymakers lifted the key rate 50 basis points today."},
            {
                "id": _C2,
                "text": "A report ranks the city among the best places to launch a startup.",
            },
        ],
    }


def _verdicts(*, c1: bool, c2: bool) -> str:
    return json.dumps(
        {
            "assessments": [
                {"id": _C1, "sameUnderlyingStory": c1, "reason": "r1"},
                {"id": _C2, "sameUnderlyingStory": c2, "reason": "r2"},
            ]
        }
    )


def _capability(prompt_library: PromptLibrary, responses: list[str]) -> NearDuplicateCapability:
    return NearDuplicateCapability(
        provider=ScriptedLlmProvider(responses), prompts=prompt_library, timeout_seconds=5.0
    )


def test_returns_one_verdict_per_comparison(prompt_library: PromptLibrary) -> None:
    handled = _capability(prompt_library, [_verdicts(c1=True, c2=False)]).handle(_payload(), _CID)

    assert handled.prompt_version == "near-duplicate/v1"
    ids = {a["id"]: a["sameUnderlyingStory"] for a in handled.result["assessments"]}
    assert ids == {_C1: True, _C2: False}


def test_distinct_result_marks_every_comparison_false(prompt_library: PromptLibrary) -> None:
    handled = _capability(prompt_library, [_verdicts(c1=False, c2=False)]).handle(_payload(), _CID)

    assert all(not a["sameUnderlyingStory"] for a in handled.result["assessments"])


def test_invalid_payload_is_rejected_before_the_provider(prompt_library: PromptLibrary) -> None:
    provider = ScriptedLlmProvider([])
    capability = NearDuplicateCapability(
        provider=provider, prompts=prompt_library, timeout_seconds=5.0
    )

    with pytest.raises(RequestInvalid):
        capability.handle({"candidate": {"text": "x"}, "comparisons": []}, _CID)  # empty list
    assert provider.calls == []


def test_missing_verdict_triggers_one_repair_then_succeeds(prompt_library: PromptLibrary) -> None:
    only_one = json.dumps(
        {"assessments": [{"id": _C1, "sameUnderlyingStory": True, "reason": "r"}]}
    )
    capability = _capability(prompt_library, [only_one, _verdicts(c1=True, c2=False)])

    handled = capability.handle(_payload(), _CID)

    assert len(handled.result["assessments"]) == 2


def test_wrong_ids_after_repair_is_output_invalid(prompt_library: PromptLibrary) -> None:
    bad = json.dumps(
        {"assessments": [{"id": "not-a-real-id", "sameUnderlyingStory": False, "reason": "r"}]}
    )
    capability = _capability(prompt_library, [bad, bad])

    with pytest.raises(OutputInvalid):
        capability.handle(_payload(), _CID)


def test_malformed_json_triggers_repair(prompt_library: PromptLibrary) -> None:
    capability = _capability(prompt_library, ["not json", _verdicts(c1=False, c2=True)])

    handled = capability.handle(_payload(), _CID)

    assert handled.result["assessments"][1]["sameUnderlyingStory"] is True


def test_capability_is_provider_independent(prompt_library: PromptLibrary) -> None:
    # The capability only ever touches the LlmProvider Protocol; swapping the
    # concrete provider changes nothing about its logic.
    provider = ScriptedLlmProvider([_verdicts(c1=True, c2=False)])
    handled = NearDuplicateCapability(
        provider=provider, prompts=prompt_library, timeout_seconds=5.0
    ).handle(_payload(), _CID)

    assert handled.provider == "scripted"
    assert len(provider.calls) == 1


# --- HTTP contract ---------------------------------------------------------


def _client(prompt_library: PromptLibrary, responses: list[str]) -> TestClient:
    capability = _capability(prompt_library, responses)
    return TestClient(create_app({capability.name: capability}))


def test_http_success_envelope(prompt_library: PromptLibrary) -> None:
    response = _client(prompt_library, [_verdicts(c1=True, c2=False)]).post(
        "/capabilities/near-duplicate/v1",
        json={
            "correlationId": _CID,
            "contractVersion": 1,
            "capability": "near-duplicate",
            "payload": _payload(),
        },
        headers={"X-Correlation-Id": _CID},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "success"
    assert body["capability"] == "near-duplicate"
    assert body["meta"]["promptVersion"] == "near-duplicate/v1"
    assert len(body["result"]["assessments"]) == 2
