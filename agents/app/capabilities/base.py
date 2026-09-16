"""Shared shape for a capability handler."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol


@dataclass(frozen=True, slots=True)
class HandledResult:
    """What a capability returns to the router, before it is wrapped in the
    response envelope."""

    result: dict[str, Any]
    prompt_version: str
    provider: str
    model: str
    duration_millis: int


class Capability(Protocol):
    name: str
    contract_version: int

    def handle(self, payload: dict[str, Any], correlation_id: str) -> HandledResult: ...
