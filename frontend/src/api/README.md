# API client

Typed client for the backend's `/api/v1` REST API (docs/03-technical-spec.md Section 12.2;
docs/04-architecture.md Section 10 — a **typed client generated from the backend's OpenAPI
document** is the approved direction, not a hand-written client).

## Layout

| Path                  | What it is                                                                                                                                                                                   |
| --------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `generated/schema.ts` | **Generated.** Produced by [`openapi-typescript`](https://openapi-ts.dev) from the backend's real OpenAPI document. Never edit by hand — regenerate it instead (below).                      |
| `types.ts`            | Hand-written type aliases onto `generated/schema.ts`'s `components['schemas']` for the schemas this client actually uses, plus `ProblemDetail` (see below). No DTO shape is duplicated here. |
| `errors.ts`           | The typed error model the client throws: `ApiNetworkError`, `ApiRequestError`, `ApiResponseShapeError`.                                                                                      |
| `client.ts`           | The actual `fetch`-based request logic and the public `api` object (`api.search(...)`, `api.askQuestion(...)`).                                                                              |
| `index.ts`            | Public entry point: `import { api } from '../api'`.                                                                                                                                          |

## Regenerating `generated/schema.ts`

The generator needs the backend's real OpenAPI document. Producing that document requires the
full Spring application context (every controller), but **not** a live database — this backend's
migrations need the pgvector extension, so a full application boot needs PostgreSQL
(Testcontainers/Docker), which client generation should not depend on. Instead, a `@WebMvcTest`
slice test (`backend/src/test/java/org/signalengine/interfaces/rest/OpenApiDocumentExportTest.java`)
loads every controller with each application use-case port mocked, fetches `/v3/api-docs`, and
writes it to `backend/build/openapi/openapi.json`. It re-exports the document on every run, so it
cannot silently go stale.

```bash
# 1. Export the real OpenAPI document (writes backend/build/openapi/openapi.json)
cd backend && ./gradlew test --tests "org.signalengine.interfaces.rest.OpenApiDocumentExportTest"

# 2. Generate the TypeScript types from it
cd ../frontend && npm run generate:api
```

Run this whenever a backend request/response DTO changes. `generated/schema.ts` is committed so
`npm ci && npm run build` works without needing the backend at all; regenerating and committing the
diff is a normal part of a change that touches `/api/v1` request or response shapes.

## Known limitation: the backend's OpenAPI annotations are not fully precise yet

Two gaps exist in what the backend's current `@Operation`/`@ApiResponse`/DTO annotations produce
in the generated document — neither is invented or worked around here; both are reported as-is:

- **Error responses are not distinctly typed.** The `@ApiResponse` entries for 4xx/5xx codes (for
  example on `SearchController`/`QuestionController`) do not declare a `content` schema, so
  springdoc reuses the 200 response's schema for every status code in the document. The real error
  body is RFC 9457 `application/problem+json`
  (`backend/.../interfaces/rest/error/ApiExceptionHandler.java`); this client models it as the
  hand-written `ProblemDetail` type in `types.ts` rather than trusting the generated (incorrect)
  error schema.
- **Nullable/optional fields are not distinguished.** Fields the Java DTOs can genuinely return as
  `null` (for example `ProvenanceResponse.originUri`/`title`/`documentId`, `CitationResponse
.quotedText`) are generated as merely optional (`field?: string`), not `string | null`, because
  the backend's `@Schema` annotations do not currently declare `nullable = true`. Consuming code
  should treat these fields defensively regardless of what the generated type alone suggests.

Both are backend OpenAPI-annotation accuracy gaps, not client bugs — fixing them (if wanted) is a
backend documentation task, out of scope here.
