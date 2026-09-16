"""Inbound HTTP request-body size limit for the AI capability service.

The service is an internal Java -> Python boundary (docs/04-architecture.md
Section 3.3), but the security baseline still requires bounded request bodies
(docs/10-security.md Section 10, 12). Pydantic field limits apply only after the
whole body has been read and JSON-parsed; this ASGI middleware caps the transport
body first.

Enforcement:

- a declared ``Content-Length`` over the limit is rejected before the app is
  invoked and before a byte of the body is read — the case for every call the
  Java client makes;
- a body streamed with no reliable declared length (chunked) is buffered only up
  to ``limit + 1`` bytes — never an unbounded buffer — and rejected if it
  exceeds the limit; otherwise the already-buffered body is replayed downstream.

A rejected request gets a ``413`` carrying the shared error envelope
(``AI_REQUEST_INVALID``, non-retryable), so the Java client classifies it
exactly like any other typed request error.
"""

from __future__ import annotations

import re

from starlette.datastructures import Headers
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.contract import ErrorCategory, failure

_CORRELATION_HEADER = "x-correlation-id"
_CAPABILITY_PATH = re.compile(r"^/capabilities/(?P<name>[^/]+)/v\d+")


class RequestBodySizeLimitMiddleware:
    def __init__(self, app: ASGIApp, *, max_request_bytes: int) -> None:
        if max_request_bytes < 1:
            raise ValueError("max_request_bytes must be positive")
        self.app = app
        self.max_request_bytes = max_request_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        declared = Headers(scope=scope).get("content-length")
        if declared is not None and declared.isdigit() and int(declared) > self.max_request_bytes:
            await self._reject(scope, send)
            return

        body = bytearray()
        trailing: list[Message] = []
        while True:
            message = await receive()
            if message["type"] != "http.request":
                trailing.append(message)
                break
            body.extend(message.get("body", b""))
            if len(body) > self.max_request_bytes:
                await self._reject(scope, send)
                return
            if not message.get("more_body", False):
                break

        buffered = bytes(body)
        replayed = False

        async def replay_receive() -> Message:
            nonlocal replayed
            if not replayed:
                replayed = True
                return {"type": "http.request", "body": buffered, "more_body": False}
            if trailing:
                return trailing.pop(0)
            return {"type": "http.disconnect"}

        await self.app(scope, replay_receive, send)

    async def _reject(self, scope: Scope, send: Send) -> None:
        headers = Headers(scope=scope)
        match = _CAPABILITY_PATH.match(scope.get("path", ""))
        envelope = failure(
            correlation_id=headers.get(_CORRELATION_HEADER) or "unknown",
            capability=match.group("name") if match else "unknown",
            code="AI_REQUEST_INVALID",
            category=ErrorCategory.REQUEST,
            retryable=False,
            message="the request body exceeds the maximum allowed size",
            details={"maxRequestBytes": self.max_request_bytes},
        )
        response = JSONResponse(status_code=413, content=envelope.model_dump(by_alias=True))
        await response(scope, _drained_receive, send)


async def _drained_receive() -> Message:
    return {"type": "http.request", "body": b"", "more_body": False}
