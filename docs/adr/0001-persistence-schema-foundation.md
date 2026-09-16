# ADR 0001 — Persistence schema foundation (PostgreSQL + Flyway)

Status: Accepted
Date: 2026-09-06
Phase: 2 (docs/11-roadmap.md Section 5), first persistence task

---

## Context

`docs/05-data-model.md` defines the **conceptual/logical** model. It explicitly
does **not** define table names, column types, indexes, foreign-key syntax, or
migrations (Section 24), and it explicitly leaves the **physical identifier
strategy** open (Section 17, Section 25). `docs/11-roadmap.md` Section 5 says
Phase 2 establishes "the PostgreSQL schema, to the extent the data model already
fixes it" and that open decisions of this kind "are resolved as implementation
work, at the point they are needed" (Section 5, Section 19).

This ADR records the implementation-level decisions made when turning the
conceptual model into the first Flyway migration set. It resolves **no** open
product or functional question.

## Problem

A physical schema cannot be created without choosing, at minimum: a primary-key
strategy, timestamp types, how much of each still-fuzzy concept to model now, and
how to represent the concepts whose vocabulary the documentation deliberately
leaves unfinished (processing state, summary status).

## Options considered

1. **Stop and report every delegated choice as blocking.** Rejected: the roadmap
   explicitly authorises implementation to resolve identifier-strategy-type
   decisions at the point of need, and a schema baseline is the stated Phase 2
   deliverable.
2. **Model every concept in the data model now, inventing shapes where the
   documentation is silent** (e.g. a full Summary table, an embeddings table with
   a chosen dimension, an Alert table). Rejected: violates "do not invent missing
   data-model decisions" and "no speculative tables/columns".
3. **Model exactly the concepts the roadmap Section 5 enumerates, using only
   shapes derivable from explicit data-model text, with the minimum set of
   delegated implementation choices, and defer the rest.** Chosen.

## Decision

### Scope of this migration set (V1–V9)

Created: `area_of_interest` (+ 6 seed rows), `source`, `interest`,
`raw_information_item` (with processing-state columns), `relevant_information`
(+ `relevant_information_area`, `relevant_information_interest`),
`signal`, `feedback`, `activity_record`, and the `vector` extension.

Deferred (not in `docs/11-roadmap.md` Section 5, or blocked by an open question):
- **Summary** table — not in the Phase 2 list; its success-state vocabulary is
  not named (`docs/05-data-model.md` Section 11). Added with Phase 6.
- **Embedding** table — dimension is open (T3) and placement is explicitly
  deferred to `docs/07-rag.md` / Phase 7 (`docs/05-data-model.md` Section 16).
  Only the extension is enabled now.
- **Alert** table — not a core concept in `docs/05-data-model.md` Section 3 / 24;
  the notification channel is open (Q19). Added with Phase 10.

### Identifier strategy

Transactional tables use `UUID PRIMARY KEY DEFAULT gen_random_uuid()`.
`area_of_interest` uses a stable `TEXT` code as a natural primary key.

### Timestamps

All time columns are `TIMESTAMPTZ`. The external-vs-system-time distinction
(`docs/05-data-model.md` Section 22) is carried by column semantics
(`published_at` vs `collected_at`, etc.), not by type.

### Deliberately unconstrained vocabularies

- `raw_information_item.processing_state` is free `TEXT` with no `CHECK`: the
  state vocabulary is explicitly "illustrative / not finalised"
  (`docs/05-data-model.md` Section 14; `docs/03-technical-spec.md` Section 10.3).
  The failure structure that *is* required — `failed_stage`, `failure_reason`,
  `failure_retryable` — is present.
- `source.type` and `activity_record.category` / `outcome` are free `TEXT`: their
  vocabularies are open (Q1) or described by example only.

### Constrained vocabularies (fixed by the documentation)

- `signal.state` — `CHECK IN ('NEW','REVIEWED','KEPT','DISMISSED')`
  (`docs/02-functional-spec.md` Section 9.3).
- `feedback.verdict` — `CHECK IN ('RELEVANT','NOT_RELEVANT')`
  (`docs/02-functional-spec.md` Section 10.2).

### Deduplication / idempotency

`raw_information_item` has
`UNIQUE NULLS NOT DISTINCT (source_id, source_provided_id, content_hash)` —
the deterministic identity of `docs/03-technical-spec.md` Section 10.4 /
`docs/05-data-model.md` Section 17. `content_hash` is `NOT NULL` (required at
collection time for idempotency).

### Source removal (Q3)

Foreign keys to `source` use PostgreSQL's default `RESTRICT`. No cascade or
nullify is chosen, because what should happen to already-collected information
when a source is removed or replaced is an open question (Q3,
`docs/05-data-model.md` Section 21).

### `updated_at`

`created_at` / `updated_at` carry `DEFAULT now()` for inserts. The application
layer will maintain `updated_at` on update once repositories / auditing exist
(D20). No database trigger — the database stays free of business logic
(`CLAUDE.md` Section 14).

### Spring wiring

`spring-boot-starter-jdbc` (a managed `DataSource` + `JdbcClient`) and
`spring-boot-flyway` only. **Spring Data JDBC and repositories are not
introduced** — they arrive with the first repository (`docs/11-roadmap.md`
sequencing; task scope).

## Reasons

- **UUID:** stable, opaque, insertion-order-independent, no enumeration exposure
  (`docs/03-technical-spec.md` Section 20), and it maps cleanly to a future
  Java-side id-generation port (`docs/03-technical-spec.md` Section 6.1) — the
  `DEFAULT` is a convenience, not a requirement. Natural keys are permitted
  (`docs/05-data-model.md` Section 17); `area_of_interest` is the one table where
  a fixed closed vocabulary makes a text code the better key (deterministic seed
  rows, readable join tables).
- **`TIMESTAMPTZ`:** unambiguous instants; the documentation leaves the type to
  implementation (`docs/05-data-model.md` Section 22).
- **Free-text state columns:** the only choice consistent with "the vocabulary is
  not final" — a `CHECK` now would be exactly the invented decision the task
  forbids.
- **Deferring Summary / Embedding / Alert:** each is either outside the Phase 2
  list or blocked by a named open question; each is a clean additive migration
  later.

## Consequences

- The schema is usable immediately for the next Phase 2 slices (repositories,
  domain mapping) without rework on the concepts it covers.
- A future Java id-generation port can supply UUIDs explicitly; the column type
  does not change.
- When `processing_state` / summary-status vocabularies are finalised, adding a
  `CHECK` is a forward-only migration; no column type changes.
- Deferred tables (Summary, Embedding, Alert) and the Q3 delete behaviour are
  added by later ADR-backed migrations.

## Trade-offs

- **UUID vs bigint:** UUID indexes are wider and (v4) not time-ordered, a minor
  write/locality cost accepted for the properties above. `gen_random_uuid()` is
  v4; a move to time-ordered UUIDs (uuidv7, PostgreSQL 18) is a possible future
  change and does not affect the column type.
- **Free-text state columns** trade database-enforced integrity for not
  pre-empting an open decision. Integrity is enforced in the Java state machine
  (`docs/05-data-model.md` Section 14; `docs/04-architecture.md` Section 12)
  until the vocabulary is fixed.
- **Deriving "last collection outcome" from `activity_record`** rather than
  denormalising onto `source` trades a slightly more expensive read for not
  adding a column whose maintenance rules are unspecified.
