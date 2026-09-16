"""The inbound request-body size limit at the AI capability HTTP boundary
(docs/10-security.md Section 10, 12; ``app.http_limits``)."""

from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path

from fastapi.testclient import TestClient

from app.capabilities.echo import EchoCapability
from app.main import create_app
from app.prompts import PromptLibrary
from app.providers.scripted import ScriptedLlmProvider

_PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"
_CID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
_LIMIT = 150
_ECHO_RESULT = json.dumps({"echoed": "hi", "characterCount": 2})


def _client(responses: list[str], *, max_request_bytes: int = _LIMIT) -> TestClient:
    capability = EchoCapability(
        provider=ScriptedLlmProvider(list(responses)),
        prompts=PromptLibrary(_PROMPTS_DIR),
        timeout_seconds=5.0,
    )
    return TestClient(
        create_app({capability.name: capability}, max_request_bytes=max_request_bytes)
    )


def _envelope_of_exact_length(total_bytes: int) -> bytes:
    skeleton = (
        '{{"correlationId":"{cid}","contractVersion":1,'
        '"capability":"echo","payload":{{"text":"hi"}}}}'
    )
    overhead = len(skeleton.format(cid="").encode("utf-8"))
    body = skeleton.format(cid="c" * (total_bytes - overhead)).encode("utf-8")
    assert len(body) == total_bytes
    return body


def _post_raw(client: TestClient, body: bytes):  # type: ignore[no-untyped-def]
    return client.post(
        "/capabilities/echo/v1",
        content=body,
        headers={"Content-Type": "application/json", "X-Correlation-Id": _CID},
    )


def _post_stream(client: TestClient, chunks: Iterator[bytes]):  # type: ignore[no-untyped-def]
    return client.post(
        "/capabilities/echo/v1",
        content=chunks,
        headers={"Content-Type": "application/json", "X-Correlation-Id": _CID},
    )


def test_a_request_below_the_limit_is_handled_normally() -> None:
    response = _post_raw(_client([_ECHO_RESULT]), _envelope_of_exact_length(_LIMIT - 20))

    assert response.status_code == 200
    assert response.json()["status"] == "success"


def test_a_request_exactly_at_the_limit_is_handled_normally() -> None:
    response = _post_raw(_client([_ECHO_RESULT]), _envelope_of_exact_length(_LIMIT))

    assert response.status_code == 200
    assert response.json()["status"] == "success"


def test_a_request_one_byte_over_the_limit_is_rejected_with_413_typed_error() -> None:
    response = _post_raw(_client([_ECHO_RESULT]), _envelope_of_exact_length(_LIMIT + 1))

    assert response.status_code == 413
    error = response.json()["error"]
    assert error["code"] == "AI_REQUEST_INVALID"
    assert error["category"] == "request"
    assert error["retryable"] is False
    assert error["correlationId"] == _CID


def test_an_oversized_request_never_reaches_the_capability_handler() -> None:
    # one scripted response: if the oversized call reached the handler it would be consumed,
    # and the following valid call would fail with "no responses".
    client = _client([_ECHO_RESULT])

    oversized = _post_raw(client, _envelope_of_exact_length(_LIMIT * 4))
    assert oversized.status_code == 413

    valid = _post_raw(client, _envelope_of_exact_length(_LIMIT - 20))
    assert valid.status_code == 200
    assert valid.json()["result"] == {"echoed": "hi", "characterCount": 2}


def test_a_chunked_body_over_the_limit_is_bounded_and_rejected() -> None:
    # an iterator body is sent with Transfer-Encoding: chunked (no Content-Length)
    def oversized_stream() -> Iterator[bytes]:
        for _ in range(8):
            yield b"x" * _LIMIT

    response = _post_stream(_client([_ECHO_RESULT]), oversized_stream())

    assert response.status_code == 413
    assert response.json()["error"]["code"] == "AI_REQUEST_INVALID"


def test_malformed_json_under_the_limit_is_still_a_400_not_a_413() -> None:
    response = _post_raw(_client([_ECHO_RESULT]), b"{ this is not valid json")

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "AI_REQUEST_INVALID"


def test_a_valid_request_under_the_limit_still_succeeds() -> None:
    body = json.dumps(
        {
            "correlationId": _CID,
            "contractVersion": 1,
            "capability": "echo",
            "payload": {"text": "hi"},
        }
    ).encode("utf-8")
    response = _post_raw(_client([_ECHO_RESULT]), body)

    assert response.status_code == 200
    assert response.json()["result"] == {"echoed": "hi", "characterCount": 2}
