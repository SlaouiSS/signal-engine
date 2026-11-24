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
from app.providers.base import ProviderError


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
    """The LLM provider could not serve the request.

    Retryable by default (provider unreachable or failing). It is not retryable when the provider
    answered with a permanent HTTP status — see :func:`to_capability_error`.
    """

    code = "AI_PROVIDER_UNAVAILABLE"
    category = ErrorCategory.PROVIDER
    retryable = True
    http_status = 503

    def __init__(
        self,
        message: str,
        *,
        details: dict[str, Any] | None = None,
        retryable: bool = True,
    ) -> None:
        super().__init__(message, details=details)
        self.retryable = retryable


class ProviderTimeout(CapabilityError):
    """The LLM provider did not respond within the configured timeout."""

    code = "AI_PROVIDER_TIMEOUT"
    category = ErrorCategory.TIMEOUT
    retryable = True
    http_status = 504


def to_capability_error(provider_error: ProviderError) -> CapabilityError:
    """The contract error for a failed provider call — the one place that mapping lives.

    A permanent HTTP status keeps the ``AI_PROVIDER_UNAVAILABLE`` code but is not retryable, and
    ``details.providerFailureScope`` tells Java whether the provider *configuration* was refused
    (``configuration``: bad credentials, retired model — it affects every request) or only this
    request was (``request``). Java suspends the capability only for the former
    (docs/adr/0018-pipeline-reliability.md).
    """
    if provider_error.timed_out:
        return ProviderTimeout(provider_error.message)
    return ProviderUnavailable(
        provider_error.message,
        details=_provider_failure_details(provider_error),
        retryable=provider_error.retryable,
    )


def _provider_failure_details(provider_error: ProviderError) -> dict[str, Any] | None:
    if provider_error.http_status is None:
        return None
    details: dict[str, Any] = {"providerStatus": provider_error.http_status}
    if not provider_error.retryable:
        details["providerFailureScope"] = (
            "configuration" if provider_error.rejects_configuration else "request"
        )
    return details
