"""Phase 1 skeleton tests: the HTTP boundary is up and OpenAPI is generated."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import create_app

client = TestClient(create_app())


def test_health_reports_ok() -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "signal-engine-agents"}


def test_openapi_document_is_served() -> None:
    response = client.get("/openapi.json")

    assert response.status_code == 200
    body = response.json()
    assert body["info"]["title"] == "Signal Engine AI Capability Service"
    assert "/health" in body["paths"]
