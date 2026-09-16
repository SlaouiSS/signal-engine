"""Typed capability failures and their mapping to the contract error envelope
(docs/03-technical-spec.md Section 13.5).

A capability raises one of these; the router turns it into an ``ErrorResponse``
with the right HTTP status. Nothing else is caught and reshaped — an unexpected
exception becomes a generic retryable ``AI_INTERNAL`` so a bug is never masked as
a business outcome (docs/03-technical-spec.md Section 13.8).
"""

from __future__ import annotations

from typing import Any

from app.contract import ErrorCategory


class CapabilityError(Exception):
    """Base class for a failure that is a defined contract outcome."""

    code: str = "AI_INTERNAL"
    category: ErrorCategory = ErrorCategory.INTERNAL
    retryable: bool = True
    http_status: int = 500

    def __init__(self, message: str, *, details: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.details = details


class RequestInvalid(CapabilityError):
    """The envelope or the capability payload does not match its schema, the
    capability is unknown, or the contract version is not supported."""

    code = "AI_REQUEST_INVALID"
    category = ErrorCategory.REQUEST
    retryable = False
    http_status = 400


class OutputInvalid(CapabilityError):
    """The model's structured output failed validation, including after the one
    bounded repair attempt (docs/03-technical-spec.md Section 9.6)."""

    code = "AI_OUTPUT_INVALID"
    category = ErrorCategory.AI_OUTPUT
    retryable = False
    http_status = 422


class ProviderUnavailable(CapabilityError):
    """The LLM provider could not be reached."""

    code = "AI_PROVIDER_UNAVAILABLE"
    category = ErrorCategory.PROVIDER
    retryable = True
    http_status = 503


class ProviderTimeout(CapabilityError):
    """The LLM provider did not respond within the configured timeout."""

    code = "AI_PROVIDER_TIMEOUT"
    category = ErrorCategory.TIMEOUT
    retryable = True
    http_status = 504
