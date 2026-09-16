# ADR 0004 — Business REST API conventions

Status: Accepted
Date: 2026-09-06
Phase: 2 (docs/11-roadmap.md), fourth persistence-phase task

---

## Context

Task 4 exposes the Task 3 application use cases over HTTP. `docs/03-technical-spec.md`
Section 6.6 / 12.1 fixes the base path (`/api/v1`), JSON, springdoc OpenAPI, and
"proposed default: RFC 7807 `application/problem+json`" errors — but not the
concrete problem-body shape, the error codes, or the routing for sub-resources
and lifecycle actions. This ADR records those so every future resource controller
is consistent.

## Decisions

### Controllers are thin inbound adapters

One controller per resource (`SourceController`, `AreaOfInterestController`,
`InterestController`, `RawInformationItemController`, `RelevantInformationController`,
`SignalController`, `ActivityController`). Each depends only on application
**input ports** by constructor injection, holds no business logic, no
persistence, no transactions, and never references a repository port or an
infrastructure type. `MetaController` remains the one technical/bootstrap
exception (CLAUDE.md Section 9).

A controller may depend on more than one input port when it owns a namespace
with sub-resources: `RelevantInformationController` uses
`ReviewRelevantInformationUseCase`, `ReviewRawInformationUseCase`, and
`ReviewSignalsUseCase` for `/relevant-information/{id}`,
`/relevant-information/{id}/raw-information-items`, and
`/relevant-information/{id}/signal`.

### Request/response DTOs

Dedicated `*Request` / `*Response` records per resource, in the controller's
package. Domain records are never the wire contract. `*Response.from(domainType)`
maps outward; `*Request` carries Jakarta Validation constraints and (where it
mirrors an application command) a `toCommand()` method.

The fixed two/four-value domain enums `FeedbackVerdict` and `SignalState` are
used directly in DTOs: they are stable functional vocabulary
(`docs/02-functional-spec.md` Section 9.3, 10.2), identical across domain, API,
and database, and duplicating them would be the mechanical DTO the task forbids.

### Not-found convention

Use cases return `Optional.empty()` for an absent target (ADR 0003). The
controller turns that into `ResourceNotFoundException`
(`org.signalengine.interfaces.rest.error`), which the advice maps to `404`. The
application layer has no not-found exception.

### Problem responses (`ApiExceptionHandler`)

A single `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`
produces every error body as `application/problem+json` with two custom
properties in addition to the RFC 9457 fields:

| Property | Meaning |
|---|---|
| `code` | stable machine-readable error code |
| `correlationId` | a value to quote when reporting the error; a fresh UUID per response until request-scoped tracing exists (D11) |

Codes used in this phase:

| Code | Status | Cause |
|---|---|---|
| `RESOURCE_NOT_FOUND` | 404 | `ResourceNotFoundException` |
| `INVALID_INPUT` | 400 | `org.signalengine.application.InvalidInputException` (blank required value, unknown reference) |
| `VALIDATION_FAILED` | 400 | Jakarta Validation failure on a request body or query parameter |
| `MALFORMED_REQUEST` | 4xx | other framework request errors (unreadable body, wrong method/media type) |
| `REQUEST_TOO_LARGE` | 413 | request body over `signal-engine.api.max-request-bytes` (default 256 KiB); rejected by a servlet filter before the body is buffered or a controller runs (`docs/10-security.md` Section 10) |
| `INTERNAL_ERROR` | 500 | any unhandled exception; logged with the correlation id, no internal detail in the body |

`InvalidInputException extends IllegalArgumentException` so the advice maps it
specifically to 400 without catching unrelated `IllegalArgumentException`s
(`docs/03-technical-spec.md` Section 13.8), and the Task 3 use-case tests, which
assert the supertype, are unchanged.

### Routing

- **Collections / items:** `GET`/`POST /api/v1/<resource>`,
  `GET`/`PUT /api/v1/<resource>/{id}`.
- **Lifecycle actions** map to the distinct use-case operations as action
  sub-resources: `POST /api/v1/sources/{id}/enable`, `.../disable`; same for
  interests. No request body, returns the updated representation.
- **Provenance sub-resources** hang off their parent:
  `/api/v1/relevant-information/{id}/raw-information-items`,
  `/api/v1/relevant-information/{id}/signal`,
  `/api/v1/signals/{id}/feedback`. A sub-resource request for a missing parent is
  `404`.
- **Bounded list:** `GET /api/v1/activity?limit=` (`1..200`, default `50`) — the
  `int maxResults` the use case requires, not offset/cursor pagination (which is
  out of scope).
- `POST` that creates a resource returns `201` with a `Location` header.

### OpenAPI

Each endpoint carries springdoc `@Operation` (summary, and description where the
behaviour is not obvious) and `@ApiResponse` for its notable statuses.
Controllers are grouped with `@Tag`.

## Consequences

- A future resource controller follows this template: input-port constructor
  injection, `*Request`/`*Response` records, `Optional.empty()` → 404 via
  `ResourceNotFoundException`, problem+json via the shared advice.
- The problem-body contract (`code`, `correlationId`) is stable; new codes are
  added to the table above, existing ones are not renamed.
- Listing/filtering for signals and pagination are not introduced (Q16;
  out-of-scope), so several read controllers only expose lookup-by-id and
  provenance navigation for now.

## Trade-offs

- **Enum reuse in DTOs** couples the API to two domain enums. Accepted: both are
  functionally fixed; the alternative is a mechanical duplicate.
- **Per-exception correlation id** is not a real trace id yet. Accepted as a
  forward-compatible placeholder until the observability work.
- **`RelevantInformationController` with three input ports** is slightly less
  "one controller, one port", but keeps the provenance sub-resources under the
  parent's URL namespace, which is the clearer REST shape.
