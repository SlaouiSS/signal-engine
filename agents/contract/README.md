# AI capability contract (v1)

The Java↔Python capability contract (`docs/03-technical-spec.md` Section 8;
`docs/adr/0006-ai-java-python-foundation.md`).

- `ai-capability.v1.schema.json` — JSON Schema generated from the Pydantic models
  in `agents/app/contract.py` (the source of truth). Regenerate with
  `python -m app.contract_schema`; `--check` fails CI if it is stale.
- `examples/` — canonical request and response envelopes. Both sides test against
  these: the Python suite validates them against the models, and the backend
  contract test replays them through the Java HTTP adapter. `backend/build.gradle.kts`
  copies this directory onto the backend test classpath, so there is one copy.

Endpoint: `POST /capabilities/{name}/v{version}` — e.g. `POST /capabilities/echo/v1`.
`echo` is the trivial capability that exists only to prove this contract; the
first real capability arrives in Task 6B.
