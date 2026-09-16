"""Shared test fixtures. No test reaches a real LLM or a network service."""

from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.capabilities.echo import EchoCapability
from app.main import create_app
from app.prompts import PromptLibrary
from app.providers.scripted import ScriptedLlmProvider

_PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"
CONTRACT_DIR = Path(__file__).resolve().parent.parent / "contract"


@pytest.fixture
def prompt_library() -> PromptLibrary:
    return PromptLibrary(_PROMPTS_DIR)


@pytest.fixture
def make_scripted() -> Callable[[list[str]], ScriptedLlmProvider]:
    return lambda responses: ScriptedLlmProvider(list(responses))


@pytest.fixture
def make_echo_client(
    prompt_library: PromptLibrary,
) -> Callable[[list[str]], TestClient]:
    def _build(responses: list[str]) -> TestClient:
        capability = EchoCapability(
            provider=ScriptedLlmProvider(list(responses)),
            prompts=prompt_library,
            timeout_seconds=5.0,
        )
        return TestClient(create_app({capability.name: capability}))

    return _build


@pytest.fixture
def valid_echo_output() -> str:
    return json.dumps({"echoed": "Signal Engine ships incrementally.", "characterCount": 34})
