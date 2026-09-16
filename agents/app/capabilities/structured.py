"""Structured-output generation with one bounded repair attempt.

The single place the repair policy lives (docs/03-technical-spec.md Section 9.6):
generate -> validate; on failure re-prompt once using the ``repair`` prompt asset
and the validation error; on a second failure raise
:class:`~app.errors.OutputInvalid`. No loop, no fabricated result. Reused by every
capability.

``extra_check`` lets a capability add a cross-field rule that depends on the
request (e.g. "one verdict per comparison"); a ``ValueError`` it raises feeds the
same one-repair loop as a schema failure.
"""

from __future__ import annotations

import json
import logging
from collections.abc import Callable

from pydantic import BaseModel, ValidationError

from app.errors import OutputInvalid, ProviderTimeout, ProviderUnavailable
from app.prompts import Prompt, RenderedPrompt
from app.providers.base import GenerationRequest, GenerationResult, LlmProvider, ProviderError

_logger = logging.getLogger("app.capabilities.structured")

# Bounds how much of a rejected model response is written to the log — the response is
# free-form model output of unbounded length (it was rejected before any length-limited
# schema field could apply), so this keeps one failure from flooding the log.
_MAX_LOGGED_RESPONSE_CHARS = 2000


def generate_structured[ResultModelT: BaseModel](
    *,
    provider: LlmProvider,
    prompt: RenderedPrompt,
    repair_prompt: Prompt,
    result_model: type[ResultModelT],
    timeout_seconds: float,
    extra_check: Callable[[ResultModelT], None] | None = None,
) -> tuple[ResultModelT, GenerationResult]:
    """Return the validated result plus the metadata of the call that produced it."""

    first = _generate(provider, prompt.system, prompt.user, timeout_seconds)
    try:
        return _parse(first.text, result_model, extra_check), first
    except _OutputRejected as first_failure:
        repair_user = repair_prompt.render(original=prompt.user, error=str(first_failure)).user
        second = _generate(provider, prompt.system, repair_user, timeout_seconds)
        try:
            return _parse(second.text, result_model, extra_check), second
        except _OutputRejected as second_failure:
            # The only diagnostic trail an AI_OUTPUT_INVALID failure leaves: both rejected
            # model responses are free-form generated text (never our request headers, API
            # key, or other credentials — this function never sees those), so logging them is
            # safe. Emitted at WARNING so it is visible under the default log level and only
            # on this exhausted-repair path — a successful call never reaches this line.
            _logger.warning(
                "structured output failed validation after one repair attempt: "
                "firstError=%s | repairedError=%s | firstResponse=%s | repairedResponse=%s",
                first_failure,
                second_failure,
                _truncated_for_log(first.text),
                _truncated_for_log(second.text),
            )
            raise OutputInvalid(
                "the model output failed validation after one repair attempt",
                details={
                    "firstError": str(first_failure),
                    "repairedError": str(second_failure),
                },
            ) from second_failure


class _OutputRejected(Exception):
    """Raised internally when one model response fails validation."""


def _generate(
    provider: LlmProvider, system: str, user: str, timeout_seconds: float
) -> GenerationResult:
    try:
        return provider.generate(
            GenerationRequest(
                system=system, user=user, json_output=True, timeout_seconds=timeout_seconds
            )
        )
    except ProviderError as provider_error:
        if provider_error.timed_out:
            raise ProviderTimeout(provider_error.message) from provider_error
        raise ProviderUnavailable(provider_error.message) from provider_error


def _parse[ResultModelT: BaseModel](
    text: str,
    result_model: type[ResultModelT],
    extra_check: Callable[[ResultModelT], None] | None,
) -> ResultModelT:
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as bad_json:
        raise _OutputRejected(f"not valid JSON: {bad_json}") from bad_json
    try:
        model = result_model.model_validate(parsed)
    except ValidationError as invalid:
        raise _OutputRejected(_summarise(invalid)) from invalid
    if extra_check is not None:
        try:
            extra_check(model)
        except ValueError as rejected:
            raise _OutputRejected(str(rejected)) from rejected
    return model


def _summarise(error: ValidationError) -> str:
    return "; ".join(
        f"{'.'.join(str(part) for part in item['loc'])}: {item['msg']}"
        for item in error.errors(include_url=False)
    )


def _truncated_for_log(text: str) -> str:
    if len(text) <= _MAX_LOGGED_RESPONSE_CHARS:
        return text
    return f"{text[:_MAX_LOGGED_RESPONSE_CHARS]}... [truncated, {len(text)} chars total]"
