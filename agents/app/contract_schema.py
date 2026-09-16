"""Emit the Java<->Python capability contract as a committed JSON Schema artifact
(docs/03-technical-spec.md Section 8.1, 16.6).

``python -m app.contract_schema`` writes ``agents/contract/ai-capability.v1.schema.json``;
``python -m app.contract_schema --check`` fails if the committed file is stale.
The Pydantic models in :mod:`app.contract` are the source of truth; this file and
the Java client records are generated/derived from them.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from pydantic import BaseModel

from app.contract import CONTRACT_VERSION, ErrorResponse, RequestEnvelope, SuccessResponse

_ARTIFACT = Path(__file__).resolve().parent.parent / "contract" / "ai-capability.v1.schema.json"


class _Contract(BaseModel):
    request: RequestEnvelope
    successResponse: SuccessResponse
    errorResponse: ErrorResponse


def build_schema() -> dict[str, Any]:
    schema = _Contract.model_json_schema(by_alias=True, mode="serialization")
    schema["title"] = "Signal Engine AI capability contract"
    schema["x-contract-version"] = CONTRACT_VERSION
    return schema


def _serialise(schema: dict[str, Any]) -> str:
    return json.dumps(schema, indent=2, sort_keys=True) + "\n"


def main(argv: list[str]) -> int:
    rendered = _serialise(build_schema())
    if "--check" in argv:
        current = _ARTIFACT.read_text(encoding="utf-8") if _ARTIFACT.exists() else ""
        if current != rendered:
            print(
                f"{_ARTIFACT} is out of date; run `python -m app.contract_schema`",
                file=sys.stderr,
            )
            return 1
        print(f"{_ARTIFACT.name} is current")
        return 0
    _ARTIFACT.parent.mkdir(parents=True, exist_ok=True)
    _ARTIFACT.write_text(rendered, encoding="utf-8")
    print(f"wrote {_ARTIFACT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
