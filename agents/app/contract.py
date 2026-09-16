"""The Java<->Python capability contract: the wire envelope shared by every AI
capability (docs/03-technical-spec.md Section 8; docs/adr/0006-ai-java-python-foundation.md).

Pydantic models here are the source of truth for the contract. The committed JSON
Schema under ``agents/contract/`` is generated from them (see
``app.contract_schema``), and the Java client records mirror them.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

CONTRACT_VERSION = 1


class _Frozen(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class RequestEnvelope(_Frozen):
    """Every capability request. ``payload`` is validated a second time against the
    target capability's own request model."""

    correlation_id: str = Field(alias="correlationId", min_length=1, max_length=200)
    contract_version: int = Field(alias="contractVersion")
    capability: str = Field(min_length=1, max_length=100)
    payload: dict[str, Any]

    model_config = ConfigDict(frozen=True, extra="forbid", populate_by_name=True)


class ErrorCategory(StrEnum):
    """Coarse grouping that drives the Java side's retry decision."""

    REQUEST = "request"
    AI_OUTPUT = "ai_output"
    PROVIDER = "provider"
    TIMEOUT = "timeout"
    INTERNAL = "internal"


class ErrorBody(_Frozen):
    code: str
    category: ErrorCategory
    retryable: bool
    message: str
    correlation_id: str = Field(alias="correlationId")
    details: dict[str, Any] | None = None

    model_config = ConfigDict(frozen=True, extra="forbid", populate_by_name=True)


class ResponseMeta(_Frozen):
    """Model/provider metadata carried on every successful response
    (docs/03-technical-spec.md Section 8.2)."""

    provider: str
    model: str
    prompt_version: str = Field(alias="promptVersion")
    duration_millis: int = Field(alias="durationMillis", ge=0)

    model_config = ConfigDict(frozen=True, extra="forbid", populate_by_name=True)


class SuccessResponse(_Frozen):
    correlation_id: str = Field(alias="correlationId")
    contract_version: int = Field(alias="contractVersion")
    capability: str
    status: Literal["success"] = "success"
    result: dict[str, Any]
    meta: ResponseMeta

    model_config = ConfigDict(frozen=True, extra="forbid", populate_by_name=True)


class ErrorResponse(_Frozen):
    correlation_id: str = Field(alias="correlationId")
    contract_version: int = Field(alias="contractVersion")
    capability: str
    status: Literal["error"] = "error"
    error: ErrorBody

    model_config = ConfigDict(frozen=True, extra="forbid", populate_by_name=True)


def success(
    *,
    correlation_id: str,
    capability: str,
    result: dict[str, Any],
    meta: ResponseMeta,
) -> SuccessResponse:
    return SuccessResponse(
        correlationId=correlation_id,
        contractVersion=CONTRACT_VERSION,
        capability=capability,
        result=result,
        meta=meta,
    )


def failure(
    *,
    correlation_id: str,
    capability: str,
    code: str,
    category: ErrorCategory,
    retryable: bool,
    message: str,
    details: dict[str, Any] | None = None,
) -> ErrorResponse:
    return ErrorResponse(
        correlationId=correlation_id,
        contractVersion=CONTRACT_VERSION,
        capability=capability,
        error=ErrorBody(
            code=code,
            category=category,
            retryable=retryable,
            message=message,
            correlationId=correlation_id,
            details=details,
        ),
    )
