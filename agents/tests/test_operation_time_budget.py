"""One operation, repair included, ends within its budget, below the Java caller's timeout
(docs/03-technical-spec.md Section 8.5; docs/adr/0019-pipeline-automation.md)."""

from __future__ import annotations

import json
import re
import time
from pathlib import Path

import httpx
import pytest
from pydantic import BaseModel, ConfigDict

from app.capabilities.structured import generate_structured
from app.errors import ProviderTimeout
from app.prompts import PromptLibrary, RenderedPrompt
from app.providers.base import GenerationRequest, GenerationResult, ProviderError
from app.providers.nvidia import NvidiaLlmProvider
from app.providers.ollama import OllamaLlmProvider

_REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class _Answer(BaseModel):
    model_config = ConfigDict(extra="forbid")

    value: int


class _SlowProvider:
    """Answers after ``seconds``, with the queued texts, and records the timeouts it was given."""

    name = "slow"
    model = "slow"

    def __init__(self, seconds: float, texts: list[str]) -> None:
        self._seconds = seconds
        self._texts = list(texts)
        self.timeouts: list[float] = []

    def generate(self, request: GenerationRequest) -> GenerationResult:
        self.timeouts.append(request.timeout_seconds)
        time.sleep(self._seconds)
        return GenerationResult(
            text=self._texts.pop(0), provider="slow", model="slow", duration_millis=1
        )


def _generate(provider: _SlowProvider, prompts: PromptLibrary, budget: float) -> _Answer:
    answer, _ = generate_structured(
        provider=provider,
        prompt=RenderedPrompt(system="s", user="produce an integer"),
        repair_prompt=prompts.load("repair", 1),
        result_model=_Answer,
        timeout_seconds=budget,
    )
    return answer


def test_the_repair_gets_only_what_the_first_call_left(prompt_library: PromptLibrary) -> None:
    provider = _SlowProvider(0.2, ["not json", json.dumps({"value": 1})])

    _generate(provider, prompt_library, 5.0)

    assert provider.timeouts[0] == 5.0
    assert provider.timeouts[1] <= 4.81


def test_no_repair_is_started_once_the_budget_is_spent(prompt_library: PromptLibrary) -> None:
    provider = _SlowProvider(0.2, ["not json", json.dumps({"value": 1})])

    with pytest.raises(ProviderTimeout):
        _generate(provider, prompt_library, 0.1)

    assert len(provider.timeouts) == 1


def test_a_hanging_repair_call_of_a_real_adapter_ends_with_the_operation_budget(
    monkeypatch: pytest.MonkeyPatch, prompt_library: PromptLibrary
) -> None:
    calls: list[float] = []

    def invalid_then_hanging(url: str, **kwargs: object) -> httpx.Response:
        calls.append(time.monotonic())
        if len(calls) == 1:
            time.sleep(0.2)
            return httpx.Response(
                200,
                json={"message": {"content": "not json"}},
                request=httpx.Request("POST", url),
            )
        time.sleep(5)
        raise AssertionError("unreachable within the test")

    monkeypatch.setattr(httpx, "post", invalid_then_hanging)
    started = time.monotonic()

    with pytest.raises(ProviderTimeout):
        generate_structured(
            provider=OllamaLlmProvider(base_url="http://provider.invalid", model="m"),
            prompt=RenderedPrompt(system="s", user="produce an integer"),
            repair_prompt=prompt_library.load("repair", 1),
            result_model=_Answer,
            timeout_seconds=0.6,
        )

    assert len(calls) == 2
    assert time.monotonic() - started < 1.5


@pytest.mark.parametrize(
    "provider",
    [
        OllamaLlmProvider(base_url="http://provider.invalid", model="m"),
        NvidiaLlmProvider(base_url="http://provider.invalid", model="m", api_key="k"),
    ],
    ids=["ollama", "nvidia"],
)
def test_a_provider_call_is_abandoned_after_its_wall_clock_time(
    monkeypatch: pytest.MonkeyPatch, provider: OllamaLlmProvider | NvidiaLlmProvider
) -> None:
    def never_ending_post(*args: object, **kwargs: object) -> httpx.Response:
        time.sleep(5)  # a response trickling in never trips httpx's per-phase timeouts
        raise AssertionError("unreachable within the test")

    monkeypatch.setattr(httpx, "post", never_ending_post)
    started = time.monotonic()

    with pytest.raises(ProviderError) as abandoned:
        provider.generate(GenerationRequest(system="s", user="u", timeout_seconds=0.2))

    assert abandoned.value.timed_out
    assert time.monotonic() - started < 2


@pytest.mark.parametrize("configuration_file", ["docker-compose.yml", ".env.example"])
def test_the_configured_python_budgets_end_before_the_java_timeout(
    configuration_file: str,
) -> None:
    text = (_REPOSITORY_ROOT / configuration_file).read_text(encoding="utf-8")
    application = (_REPOSITORY_ROOT / "backend/src/main/resources/application.yml").read_text(
        encoding="utf-8"
    )
    java_timeout = re.search(r"AI_REQUEST_TIMEOUT:(\d+)s", application)
    assert java_timeout is not None

    for name in ("AGENTS_LLM_REQUEST_TIMEOUT_SECONDS", "EMBEDDING_REQUEST_TIMEOUT_SECONDS"):
        configured = re.search(rf"{name}\s*[:=]\s*(?:\$\{{{name}:-)?(\d+)", text)
        assert configured is not None, f"{name} not found in {configuration_file}"
        assert int(configured.group(1)) < int(java_timeout.group(1))
