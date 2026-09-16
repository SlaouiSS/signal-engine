"""The ``POST /capabilities/{name}/v{version}`` endpoint end to end (deterministic
scripted provider, no LLM)."""

from __future__ import annotations

import json
from collections.abc import Callable
from typing import Any

from fastapi.testclient import TestClient

from app.capabilities.base import HandledResult
from app.main import create_app

_CID = "11111111-2222-3333-4444-555555555555"
_VALID = json.dumps({"echoed": "hi there", "characterCount": 8})


def _request(
    payload: dict[str, object], *, capability: str = "echo", version: int = 1
) -> dict[str, object]:
    return {
        "correlationId": _CID,
        "contractVersion": version,
        "capability": capability,
        "payload": payload,
    }


def _post(client: TestClient, body: dict[str, object], path: str = "/capabilities/echo/v1"):  # type: ignore[no-untyped-def]
    return client.post(path, json=body, headers={"X-Correlation-Id": _CID})


def test_success_envelope(make_echo_client: Callable[[list[str]], TestClient]) -> None:
    response = _post(make_echo_client([_VALID]), _request({"text": "hi there"}))

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "success"
    assert body["correlationId"] == _CID
    assert body["contractVersion"] == 1
    assert body["capability"] == "echo"
    assert body["result"] == {"echoed": "hi there", "characterCount": 8}
    assert body["meta"]["promptVersion"] == "echo/v1"
    assert body["meta"]["provider"] == "scripted"


def test_one_bounded_repair_then_success(
    make_echo_client: Callable[[list[str]], TestClient],
) -> None:
    bad = json.dumps({"echoed": "hi there", "characterCount": 999})
    response = _post(make_echo_client([bad, _VALID]), _request({"text": "hi there"}))

    assert response.status_code == 200
    assert response.json()["result"]["characterCount"] == 8


def test_output_invalid_after_repair_is_422_non_retryable(
    make_echo_client: Callable[[list[str]], TestClient],
) -> None:
    bad = json.dumps({"echoed": "hi there", "characterCount": 999})
    response = _post(make_echo_client([bad, bad]), _request({"text": "hi there"}))

    assert response.status_code == 422
    error = response.json()["error"]
    assert error["code"] == "AI_OUTPUT_INVALID"
    assert error["category"] == "ai_output"
    assert error["retryable"] is False
    assert error["correlationId"] == _CID


def test_malformed_envelope_is_400_request_invalid(
    make_echo_client: Callable[[list[str]], TestClient],
) -> None:
    response = make_echo_client([_VALID]).post(
        "/capabilities/echo/v1",
        json={"capability": "echo"},  # missing correlationId, contractVersion, payload
        headers={"X-Correlation-Id": _CID},
    )

    assert response.status_code == 400
    error = response.json()["error"]
    assert error["code"] == "AI_REQUEST_INVALID"
    assert error["retryable"] is False
    assert error["correlationId"] == _CID


def test_completely_malformed_json_body_is_400_request_invalid(
    make_echo_client: Callable[[list[str]], TestClient],
) -> None:
    response = make_echo_client([_VALID]).post(
        "/capabilities/echo/v1",
        content=b"{ this is not valid json",
        headers={"Content-Type": "application/json", "X-Correlation-Id": _CID},
    )

    assert response.status_code == 400
    error = response.json()["error"]
    assert error["code"] == "AI_REQUEST_INVALID"
    assert error["category"] == "request"
    assert error["retryable"] is False
    assert error["correlationId"] == _CID


def test_non_json_body_is_400_request_invalid(
    make_echo_client: Callable[[list[str]], TestClient],
) -> None:
    # A text/plain body reaches Pydantic as raw bytes; the error envelope must
    # still render, as a 400 rather than a retryable 500.
    response = make_echo_client([_VALID]).post(
        "/capabilities/echo/v1",
        content=b"plain text, not an envelope",
        headers={"Content-Type": "text/plain", "X-Correlation-Id": _CID},
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "AI_REQUEST_INVALID"


def test_non_utf8_request_body_is_400_not_500(
    make_echo_client: Callable[[list[str]], TestClient],
) -> None:
    # Pydantic echoes the raw request bytes as the offending ``input``. Here they
    # are not UTF-8 and not JSON-serialisable; rendering the error envelope must
    # not fail (this was the reported 500).
    response = make_echo_client([_VALID]).post(
        "/capabilities/echo/v1",
        content=b"\xff\xfe\x00\x80\x81 not an envelope",
        headers={"Content-Type": "text/plain", "X-Correlation-Id": _CID},
    )

    assert response.status_code == 400
    body = response.json()
    assert body["status"] == "error"
    assert body["error"]["code"] == "AI_REQUEST_INVALID"


def test_empty_body_is_400_request_invalid(
    make_echo_client: Callable[[list[str]], TestClient],
) -> None:
    response = make_echo_client([_VALID]).post(
        "/capabilities/echo/v1",
        content=b"",
        headers={"Content-Type": "application/json", "X-Correlation-Id": _CID},
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "AI_REQUEST_INVALID"


def test_request_validation_details_are_json_safe_primitives(
    make_echo_client: Callable[[list[str]], TestClient],
) -> None:
    response = make_echo_client([_VALID]).post(
        "/capabilities/echo/v1",
        content=b"{not json",
        headers={"Content-Type": "application/json", "X-Correlation-Id": _CID},
    )

    errors = response.json()["error"]["details"]["errors"]
    assert isinstance(errors, list) and errors
    for item in errors:
        assert set(item).issubset({"type", "loc", "msg", "ctx"})
        assert "input" not in item
        assert isinstance(item["type"], str)
        assert isinstance(item["msg"], str)
        assert isinstance(item["loc"], list)
        assert all(isinstance(part, str) for part in item["loc"])


class _RaisingCapability:
    """A capability whose handler has a bug — used to prove an unexpected error
    is still a retryable 500 ``AI_INTERNAL`` and is never reshaped into a 400."""

    name = "boom"
    contract_version = 1

    def handle(self, payload: dict[str, Any], correlation_id: str) -> HandledResult:
        raise RuntimeError("a bug that must not be reshaped into a client error")


def test_unrelated_internal_exception_stays_500_not_400() -> None:
    client = TestClient(create_app({"boom": _RaisingCapability()}), raise_server_exceptions=False)

    response = client.post(
        "/capabilities/boom/v1",
        json={
            "correlationId": _CID,
            "contractVersion": 1,
            "capability": "boom",
            "payload": {},
        },
        headers={"X-Correlation-Id": _CID},
    )

    assert response.status_code == 500
    error = response.json()["error"]
    assert error["code"] == "AI_INTERNAL"
    assert error["category"] == "internal"
    assert error["retryable"] is True


def test_invalid_capability_payload_is_400_request_invalid(
    make_echo_client: Callable[[list[str]], TestClient],
) -> None:
    response = _post(make_echo_client([_VALID]), _request({"text": ""}))  # min_length 1

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "AI_REQUEST_INVALID"


def test_unknown_capability_is_400(make_echo_client: Callable[[list[str]], TestClient]) -> None:
    response = _post(
        make_echo_client([_VALID]),
        _request({"text": "x"}, capability="summarize"),
        path="/capabilities/summarize/v1",
    )

    assert response.status_code == 400
    assert "unknown capability" in response.json()["error"]["message"]


def test_contract_version_mismatch_is_400(
    make_echo_client: Callable[[list[str]], TestClient],
) -> None:
    response = _post(
        make_echo_client([_VALID]),
        _request({"text": "x"}, version=2),
        path="/capabilities/echo/v2",
    )

    assert response.status_code == 400
    assert "contract version" in response.json()["error"]["message"]


def test_provider_unavailable_is_503_retryable(
    make_echo_client: Callable[[list[str]], TestClient],
) -> None:
    response = _post(make_echo_client([]), _request({"text": "hi there"}))  # scripted: no responses

    assert response.status_code == 503
    error = response.json()["error"]
    assert error["code"] == "AI_PROVIDER_UNAVAILABLE"
    assert error["retryable"] is True
