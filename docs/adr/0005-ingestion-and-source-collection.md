# ADR 0005 — Ingestion and source collection foundation

Status: Accepted
Date: 2026-09-06
Phase: 2 (docs/11-roadmap.md), fifth task — Ingestion and Source Collection

---

## Context

Task 5 builds the Java ingestion foundation: collect information from a configured
`Source`, turn what is collected into persisted **Raw Information Items** with their
provenance, and make that operation safe to re-run. Java owns orchestration,
deterministic processing, persistence, and the scheduling boundary; AI processing
(classification, relevance, importance, summarisation, semantic near-duplicate
detection) is later work.

The spec fixes the boundary but leaves much open:

- `docs/03-technical-spec.md` Section 10.2 fixes a `SourceCollector` **port** in the
  application layer with one adapter per source type in infrastructure; the concrete
  source-type catalogue is **Q1 (open)**.
- `docs/08-ingestion.md` Sections 4–8, 13–15, 17–18, 21 describe collection,
  normalisation, exact deduplication, provenance, processing state, failure handling,
  and untrusted-content handling **conceptually**, explicitly not fixing rule sets,
  field lists, or a connector framework.
- `docs/03-technical-spec.md` Section 20.2 and `docs/10-security.md` Sections 5–7
  make SSRF/size/redirect/timeout/encoding protection a **required consideration** at
  the collector boundary, with no library and no numeric limits chosen.
- Scheduling cadence is **Q9 (open)**; a manual "collect now" trigger is **Q8 / T17
  (open)**; per-source retry/backoff is **Q10 (open)**; the minimum visible metadata
  set is **Q11 (open)**; the near-duplicate threshold is **Q12 (open)**; the physical
  identifier strategy is **`docs/05-data-model.md` Section 17 (open)**.

This ADR records the decisions made to produce a working vertical slice without
resolving any of those questions.

## Decisions

### The ingestion pipeline is an application use case over ports

`org.signalengine.application.ingestion` holds the orchestration and its ports, and
depends only on domain types and other application ports — no Spring, no HTTP client,
no JDBC, no SQL.

| Type | Role |
|---|---|
| `CollectFromSourceUseCase` | input port: `collectFromSource(UUID)` and `collectFromEnabledSources()` |
| `SourceCollector` | output port: `CollectionOutcome collect(Source)` — one adapter per source type |
| `SourceCollectorRegistry` | output port: `Optional<SourceCollector> collectorFor(Source)` — empty when no collector is registered for the source's type (Q1) |
| `ContentNormalizer` | output port: deterministic `String normalize(String)` |
| `CollectedItem` | one unit of collected information with provenance, before normalisation/persistence |
| `CollectionOutcome` | sealed: `Collected(items)` or `CollectionFailed(reason, retryable)` |
| `CollectionReport` | per-source result for a caller (a future scheduler): `COLLECTED` / `SKIPPED_DISABLED` / `NO_COLLECTOR` / `FAILED` |

`DefaultCollectFromSourceUseCase` is a plain class; `infrastructure/ingestion/IngestionConfiguration`
`@Bean`-wires it (composition-root pattern, as ADR 0003 / `UseCaseConfiguration`).

The orchestration never names a concrete collector. Adding a source type is a new
adapter plus one line of configuration — no change to the use case, normalisation,
deduplication, or persistence (`docs/03-technical-spec.md` Section 10.2, 21.2).

### One concrete collector: HTTP(S) fetch

`HttpSourceCollector` (infrastructure) is the only concrete collector. It fetches the
source's `reference` URL with the JDK `java.net.http.HttpClient` (no dependency added
— CLAUDE.md Section 23) and returns the response body as a **single** `CollectedItem`.

It does **no** format-specific parsing — no HTML body extraction, no feed parsing.
Response-shape extraction and multi-item sources are per-source-type work deferred
with Q1 (`docs/08-ingestion.md` Section 6). `sourceProvidedId` is therefore `null` for
this collector; `title` is `null`; `publishedAt` is taken from the `Last-Modified`
header when present; `mediaType` is the response `Content-Type` (recorded, not yet
enforced against an allow-list — the valid types depend on Q1).

`ConfigurableSourceCollectorRegistry` binds this collector to the source types listed
in `signal-engine.ingestion.http-collector-source-types` (default: `http`). **This is
the Q1 extension point, not a resolution of Q1** — it is a provisional binding so the
slice has something to collect. A source of any other type resolves to no collector
and the run reports `NO_COLLECTOR` (recorded as an activity failure); the source is
left untouched.

### Deterministic normalisation, raw content preserved

`DeterministicContentNormalizer` applies, in order: Unicode NFC, canonical `\n` line
endings, removal of control characters other than tab and newline, per-line
trailing-whitespace trimming, collapsing 3+ blank lines to one, and an overall trim.
No library, no locale, no clock, no randomness — `normalize` is a pure function and is
idempotent.

It does not parse or strip markup. The **raw** content is stored unchanged alongside
the normalised content (`raw_content` and `normalized_content` columns already exist —
migration V5); normalisation changes representation, never the stored original
(`docs/08-ingestion.md` Section 7).

`language` is left `null`: there is no detection library (none is approved), no
source-declared language for an HTTP fetch, and adding a column or a dependency is out
of scope. Deferred with Q11 / `docs/02-functional-spec.md` Section 15.3.

### Exact deduplication by deterministic identity, via the existing constraint

The dedup key is the identity already in the schema:
`(source_id, source_provided_id, content_hash)`, where `content_hash` is the SHA-256
(hex) of the **normalised** content (`docs/03-technical-spec.md` Section 10.4;
`docs/05-data-model.md` Section 17; migration V5 `UNIQUE NULLS NOT DISTINCT`).

Before persisting, the use case calls the
`RawInformationItemRepository.findByIdentity(sourceId, sourceProvidedId, contentHash)`
application port (implemented with `IS NOT DISTINCT FROM` to match `NULLS NOT
DISTINCT`) as a fast path. A hit is counted as a duplicate and **nothing is written**.
The write itself goes through `RawInformationItemRepository.saveIfNew(...)`: the
`raw_information_item_identity_key` unique constraint is the **final authority**, so
when two concurrent collections both pass the pre-check, the one whose insert loses
the race is recognised (by that constraint's `23505` violation) as an exact duplicate
— the same idempotent outcome, not a collection failure. Re-collecting the same item
from the same source converges, sequentially or concurrently (`docs/08-ingestion.md`
Section 15). Any other integrity violation still surfaces as a real failure.

Not done here, because they need still-open decisions:

- **Cross-source exact match** (`docs/03-technical-spec.md` Section 10.5 / R5:
  "attached to the existing item/group with its source reference"). A
  `RawInformationItem` belongs to exactly one `Source` (`docs/05-data-model.md`
  Section 8); representing "the same content, seen from source B" needs the physical
  identifier strategy (Section 17, open) and probably a schema change (out of scope).
  For now, identical content from a different source is a separate item.
- **A distinct `DUPLICATE` processing state** for the rejected copy — there is no
  second row to carry it.
- **Semantic near-duplicate detection** — explicitly a later, AI task (Q12).

### Processing state: the smallest provisional representation

> **Superseded by ADR 0007 (Task 6B).** The terminal state for a freshly
> collected, exact-deduped item is now `normalized` (awaiting semantic
> near-duplicate assessment), not `deduplicated`. `deduplicated` now means
> "semantically distinct, anchors its own Relevant Information record". The rest
> of this section is retained as written at the time.

A persisted item is written with `ProcessingState.deduplicated(collectedAt)` —
`state = "deduplicated"`, no failure fields. The string is **provisional**, taken from
the illustrative sequence `received → normalized → deduplicated → …`
(`docs/03-technical-spec.md` Section 10.3; `docs/05-data-model.md` Section 14, which
both mark the vocabulary as not finalised). A `ProcessingState.failed(stage, reason,
retryable, at)` factory is added for the failure shape the spec does require
(`docs/03-technical-spec.md` Section 13.1–13.2), used by later stages.

No state machine, no enum, no AI-processing states are introduced. The three
distinctions the task needs are expressible now:

| Situation | Representation |
|---|---|
| not yet semantically processed | a persisted row, `processing_state = 'deduplicated'`, `relevant_information_id` null |
| successfully collected + persisted | the row exists (found by identity) |
| collection/processing failed | **no row** for this task's stages; a `collection` `ActivityRecord` with `outcome = 'failure'` and the reason |

Failed collection does not persist a Raw Information Item: this task's only stages
(fetch, normalise, hash, dedup) produce an item **or** a recorded failure, so there is
no "failed item awaiting an identity" case. Later AI stages, which fail *after* an
item exists, will use the `failed(...)` state on the existing row.

### Failure handling follows the existing taxonomy

`CollectionOutcome.CollectionFailed` carries `retryable`, mapped from
`docs/03-technical-spec.md` Section 13.1–13.2:

| Cause | `retryable` |
|---|---|
| source unreachable, connection error, read error, timeout | `true` |
| HTTP 429 or 5xx from the source | `true` |
| HTTP 4xx (other than 429) | `false` |
| unsafe / malformed / unresolvable URL | `false` |
| response over the size limit | `false` |
| body not decodable in the declared charset | `false` |
| blank body — "no usable content" | `false` (Section 13.2 names this case) |

Every failure is recorded as a `collection` `ActivityRecord` and returned in the
`CollectionReport`; nothing is swallowed (`docs/08-ingestion.md` Section 4, 18).
`collectFromEnabledSources()` additionally catches an unexpected `RuntimeException`
per source, logs it, records it, and continues with the other sources
(`docs/08-ingestion.md` Section 17). No retry infrastructure is added — a retryable
source failure is simply retried on the next collection run (`docs/03-technical-spec.md`
Section 13.3); the per-source backoff/counter is Q10.

### SSRF / URL safety: hand-rolled, at the collector boundary

`OutboundUrlValidator` (infrastructure, package-private) is applied before the first
request **and to every redirect target**. No third-party library is used — none is
approved (`docs/10-security.md` Section 6).

Checks: the URL parses and is absolute; its scheme is in the configured allow-list
(default `https` only); it has a host; and **every** address the host resolves to
(`InetAddress.getAllByName`) is public unicast — rejecting loopback, any-local,
link-local (which covers the `169.254.169.254` cloud-metadata address), site-local /
RFC 1918, IPv6 unique-local (`fc00::/7`), and multicast.

`HttpClient` is built with `Redirect.NEVER`; redirects are followed manually, bounded
by `signal-engine.ingestion.max-redirects` (default 3), each hop re-validated. The
response is bounded by `max-response-bytes` (default 5 MiB, checked against both the
`Content-Length` header and the bytes actually read) and by connect/request timeouts
(default 10 s / 30 s). The body is decoded strictly (`CodingErrorAction.REPORT`) in
the charset from `Content-Type`, defaulting to UTF-8 — an invalid encoding is a
failure, never guessed (`docs/10-security.md` Section 7). Only the host is logged, not
the full URL or the body (`docs/10-security.md` Section 16).

**DNS rebinding — closed by ADR 0017.** The HTTP client used to re-resolve the host
when it connected, so a hostname whose DNS answer changed between validation and
connection was a residual SSRF bypass. `docs/adr/0017-outbound-fetch-ssrf-dns-rebinding.md`
closes this: `OutboundUrlValidator.validate` now returns the validated addresses, the
collector pins `hostname → addresses` for the duration of each `HttpClient` call, and a
JVM `InetAddressResolver` provider answers that hostname from the pin — so the client
connects to an address that was SSRF-checked, with the original hostname (and therefore
TLS SNI and certificate verification) preserved. Network egress filtering is documented
there as production defence in depth.

All numeric limits are `@ConfigurationProperties` on `IngestionProperties` with
`@DefaultValue`s and env-var overrides in `application.yml` — provisional defaults
open to tuning (T9), not decisions.

A package-private `OutboundUrlValidator(schemes, rejectPrivateAddresses)` constructor
exists **only** so a test can point the collector at a loopback server; production
wiring always rejects private addresses.

### No scheduler, no new endpoint

`CollectFromSourceUseCase` is callable explicitly so a future in-process scheduler
(Q9) or manual trigger (Q8 / T17) can invoke it. This task adds neither a scheduler
nor a REST endpoint — the functional spec does not currently require a manual
ingestion endpoint.

### Disabled sources

`collectFromSource` returns `SKIPPED_DISABLED` for a disabled source without touching
the collector or the repositories, and records no activity. Already-collected items
are never deleted (`docs/02-functional-spec.md` R7). Source removal/replacement (Q3)
is not implemented.

## Consequences

- A new source type = a new `SourceCollector` adapter + one config entry. The
  application layer and every downstream stage are untouched.
- `RawInformationItemRepository` gains `findByIdentity` and `saveIfNew`; the Spring
  Data JDBC adapter and its crud repository gain the matching `IS NOT DISTINCT FROM`
  query, and the adapter maps a `raw_information_item_identity_key` `23505` violation
  to the duplicate outcome. No schema change.
- `ProcessingState` gains provisional `deduplicated` / `failed` string constants and
  factories. When the state vocabulary is finalised (`docs/05-data-model.md`
  Section 14), these strings and `DefaultCollectFromSourceUseCase` change together.
- `signal-engine.ingestion.*` is a new configuration namespace, all provisional.
- Failure is observable through `ActivityRecord`s only; there is still no dashboard
  (`docs/08-ingestion.md` Section 18).

## Trade-offs

- **HTTP-fetch-only, no parsing.** The slice can collect from a source that serves its
  content as one text document at a stable URL, and nothing more. Accepted: a useful
  end-to-end path without pre-empting Q1. Feed/HTML/API collectors are additive.
- **Provisional source-type binding (`http`).** Looks like a source-type decision;
  it is a config default, documented as such, and changing it is a one-line edit.
- **Hand-rolled SSRF checks.** More code to own and test than a library, but no
  approved library exists and the boundary is small and explicit.
- **Duplicate = silent no-op.** Simpler than creating a `DUPLICATE`-state record or
  merging source references, and correct for the same-source re-collection case that
  the idempotency requirement is actually about. Cross-source merging waits for the
  identifier strategy.
- **`language` left null.** Honest about the absence of a detection mechanism rather
  than guessing.
- **Test-only constructor seam on `OutboundUrlValidator`.** A small production
  concession for testability; the seam is package-private and documented.
