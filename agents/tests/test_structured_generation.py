"""The structured-output + one-bounded-repair mechanism."""

from __future__ import annotations

import json
import logging
from typing import Any

import httpx
import pytest
from pydantic import BaseModel, ConfigDict

from app.capabilities.structured import generate_structured
from app.errors import OutputInvalid, ProviderTimeout, ProviderUnavailable
from app.prompts import PromptLibrary, RenderedPrompt
from app.providers.base import GenerationRequest, GenerationResult, ProviderError
from app.providers.nvidia import NvidiaLlmProvider


class _Answer(BaseModel):
    model_config = ConfigDict(extra="forbid")

    value: int


class _FlakyProvider:
    """Returns queued responses; a response may be a string or a ProviderError."""

    def __init__(self, responses: list[str | ProviderError]) -> None:
        self._responses = list(responses)
        self.calls = 0

    name = "flaky"
    model = "flaky"

    def generate(self, request: GenerationRequest) -> GenerationResult:
        self.calls += 1
        nxt = self._responses.pop(0)
        if isinstance(nxt, ProviderError):
            raise nxt
        return GenerationResult(text=nxt, provider="flaky", model="flaky", duration_millis=1)


@pytest.fixture
def prompt(prompt_library: PromptLibrary) -> RenderedPrompt:
    return RenderedPrompt(system="s", user="produce an integer")


@pytest.fixture
def repair(prompt_library: PromptLibrary):  # type: ignore[no-untyped-def]
    return prompt_library.load("repair", 1)


def test_valid_first_response_is_returned_without_repair(prompt, repair) -> None:  # type: ignore[no-untyped-def]
    provider = _FlakyProvider([json.dumps({"value": 7})])

    answer, meta = generate_structured(
        provider=provider,
        prompt=prompt,
        repair_prompt=repair,
        result_model=_Answer,
        timeout_seconds=1,
    )

    assert answer.value == 7
    assert provider.calls == 1
    assert meta.provider == "flaky"


def test_one_repair_attempt_recovers_an_invalid_first_response(prompt, repair) -> None:  # type: ignore[no-untyped-def]
    provider = _FlakyProvider(["not json at all", json.dumps({"value": 3})])

    answer, _ = generate_structured(
        provider=provider,
        prompt=prompt,
        repair_prompt=repair,
        result_model=_Answer,
        timeout_seconds=1,
    )

    assert answer.value == 3
    assert provider.calls == 2


def test_second_invalid_response_raises_output_invalid(prompt, repair) -> None:  # type: ignore[no-untyped-def]
    provider = _FlakyProvider(["nope", json.dumps({"value": "still-bad"})])

    with pytest.raises(OutputInvalid) as caught:
        generate_structured(
            provider=provider,
            prompt=prompt,
            repair_prompt=repair,
            result_model=_Answer,
            timeout_seconds=1,
        )

    assert provider.calls == 2
    assert caught.value.retryable is False
    assert set(caught.value.details or {}) == {"firstError", "repairedError"}


def test_provider_timeout_is_surfaced_as_provider_timeout(prompt, repair) -> None:  # type: ignore[no-untyped-def]
    provider = _FlakyProvider([ProviderError("slow", retryable=True, timed_out=True)])

    with pytest.raises(ProviderTimeout):
        generate_structured(
            provider=provider,
            prompt=prompt,
            repair_prompt=repair,
            result_model=_Answer,
            timeout_seconds=1,
        )


def test_provider_error_is_surfaced_as_provider_unavailable(prompt, repair) -> None:  # type: ignore[no-untyped-def]
    provider = _FlakyProvider([ProviderError("down", retryable=True)])

    with pytest.raises(ProviderUnavailable):
        generate_structured(
            provider=provider,
            prompt=prompt,
            repair_prompt=repair,
            result_model=_Answer,
            timeout_seconds=1,
        )


# --- AI_OUTPUT_INVALID diagnostic logging -----------------------------------
#
# The investigation into AI_OUTPUT_INVALID failures found that Python computes exactly why a
# response was rejected (OutputInvalid.details) but never logs it, and that the raw rejected
# model response was never captured anywhere. These tests lock in the fix: the failure now
# leaves a diagnosable trail in the log, without ever risking a leaked secret.

_STRUCTURED_LOGGER = "app.capabilities.structured"


def test_output_invalid_logs_the_first_and_repaired_validation_errors(  # type: ignore[no-untyped-def]
    caplog: pytest.LogCaptureFixture, prompt, repair
) -> None:
    caplog.set_level(logging.WARNING, logger=_STRUCTURED_LOGGER)
    provider = _FlakyProvider(["not json at all", json.dumps({"value": "still-bad"})])

    with pytest.raises(OutputInvalid):
        generate_structured(
            provider=provider,
            prompt=prompt,
            repair_prompt=repair,
            result_model=_Answer,
            timeout_seconds=1,
        )

    [record] = [r for r in caplog.records if r.name == _STRUCTURED_LOGGER]
    assert record.levelno == logging.WARNING
    message = record.getMessage()
    assert "not valid JSON" in message  # the first attempt's rejection reason
    assert "value: Input should be a valid integer" in message  # the repair's rejection reason


def test_output_invalid_logs_the_rejected_raw_model_responses(  # type: ignore[no-untyped-def]
    caplog: pytest.LogCaptureFixture, prompt, repair
) -> None:
    caplog.set_level(logging.WARNING, logger=_STRUCTURED_LOGGER)
    provider = _FlakyProvider(["THIS IS NOT JSON", "{ALSO NOT JSON"])

    with pytest.raises(OutputInvalid):
        generate_structured(
            provider=provider,
            prompt=prompt,
            repair_prompt=repair,
            result_model=_Answer,
            timeout_seconds=1,
        )

    [record] = [r for r in caplog.records if r.name == _STRUCTURED_LOGGER]
    message = record.getMessage()
    assert "THIS IS NOT JSON" in message
    assert "{ALSO NOT JSON" in message


def test_output_invalid_log_truncates_an_overlong_rejected_response(  # type: ignore[no-untyped-def]
    caplog: pytest.LogCaptureFixture, prompt, repair
) -> None:
    caplog.set_level(logging.WARNING, logger=_STRUCTURED_LOGGER)
    overlong = "x" * 5_000
    provider = _FlakyProvider([overlong, overlong])

    with pytest.raises(OutputInvalid):
        generate_structured(
            provider=provider,
            prompt=prompt,
            repair_prompt=repair,
            result_model=_Answer,
            timeout_seconds=1,
        )

    [record] = [r for r in caplog.records if r.name == _STRUCTURED_LOGGER]
    message = record.getMessage()
    assert "x" * 5_000 not in message  # never logged unbounded
    assert "truncated, 5000 chars total" in message


def test_output_invalid_never_logs_the_provider_api_key(  # type: ignore[no-untyped-def]
    caplog: pytest.LogCaptureFixture,
    monkeypatch: pytest.MonkeyPatch,
    prompt,
    repair,
) -> None:
    """End-to-end through the real NVIDIA adapter: the secret used to authenticate must not
    appear anywhere in the log output produced by an AI_OUTPUT_INVALID failure."""
    caplog.set_level(logging.WARNING)
    secret = "sk-super-secret-do-not-log-8f3c2a"  # pragma: allowlist secret

    def rejects_everything(url: str, **kwargs: Any) -> httpx.Response:
        assert kwargs["headers"]["Authorization"] == f"Bearer {secret}"
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "not json"}}]},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx, "post", rejects_everything)
    provider = NvidiaLlmProvider(base_url="https://nvidia.test/v1", model="m", api_key=secret)

    with pytest.raises(OutputInvalid):
        generate_structured(
            provider=provider,
            prompt=prompt,
            repair_prompt=repair,
            result_model=_Answer,
            timeout_seconds=1,
        )

    all_log_text = "\n".join(r.getMessage() for r in caplog.records)
    assert secret not in all_log_text
    assert "Authorization" not in all_log_text
    assert "Bearer" not in all_log_text
