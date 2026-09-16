"""The one capability endpoint: ``POST /capabilities/{name}/v{version}``.

The route parses the shared request envelope, dispatches to the registered
capability, and wraps the outcome in the shared response envelope. Typed failures
are turned into an error envelope by the exception handlers in ``app.main``.
"""

from __future__ import annotations

from fastapi import APIRouter, Request

from app.capabilities.base import Capability
from app.contract import RequestEnvelope, ResponseMeta, SuccessResponse, success
from app.errors import RequestInvalid

router = APIRouter(prefix="/capabilities", tags=["capabilities"])


@router.post("/{name}/v{version}", response_model=SuccessResponse)
def invoke_capability(
    name: str, version: int, envelope: RequestEnvelope, request: Request
) -> SuccessResponse:
    capabilities: dict[str, Capability] = request.app.state.capabilities
    capability = capabilities.get(name)
    if capability is None:
        raise RequestInvalid(f"unknown capability '{name}'")
    if version != capability.contract_version:
        raise RequestInvalid(f"capability '{name}' does not support contract version {version}")
    if envelope.capability != name:
        raise RequestInvalid(
            f"envelope capability '{envelope.capability}' does not match the URL '{name}'"
        )
    if envelope.contract_version != version:
        raise RequestInvalid(
            f"envelope contractVersion {envelope.contract_version} "
            f"does not match the URL v{version}"
        )

    handled = capability.handle(envelope.payload, envelope.correlation_id)
    return success(
        correlation_id=envelope.correlation_id,
        capability=name,
        result=handled.result,
        meta=ResponseMeta(
            provider=handled.provider,
            model=handled.model,
            promptVersion=handled.prompt_version,
            durationMillis=handled.duration_millis,
        ),
    )
