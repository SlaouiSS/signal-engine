# ADR 0002 — Persistence aggregate boundaries (Spring Data JDBC)

Status: Accepted
Date: 2026-09-06
Phase: 2 (docs/11-roadmap.md Section 5), second persistence task

---

## Context

Spring Data JDBC persists **aggregates**: an aggregate root and the child rows it
owns are loaded and saved together, while references to *other* aggregates are
held by id, not by object. Choosing which tables form one aggregate is therefore
unavoidable when adding the Spring Data JDBC layer.

`docs/05-data-model.md` is a conceptual model and **does not define aggregate
boundaries**. ADR 0001 states "Do not invent aggregate boundaries." The Phase 2
Task 2 brief repeats this and asks to STOP if the mapping forces a DDD decision
the data model has not made.

## Problem

Eight concepts are in the schema (`source`, `interest`, `area_of_interest`,
`raw_information_item`, `relevant_information`, `signal`, `feedback`,
`activity_record`) plus two link tables (`relevant_information_area`,
`relevant_information_interest`). Some pairs *could* be mapped as
root-owns-children:

- `relevant_information` ← `raw_information_item.relevant_information_id`
- `relevant_information` ← `signal.relevant_information_id` (1:1)
- `signal` ← `feedback.signal_id`
- `relevant_information` ← the two link tables

## Options considered

1. **One large aggregate rooted at `relevant_information`** owning raw items,
   the signal, and (transitively) feedback. Rejected: a `raw_information_item`
   exists the moment collection succeeds, long before any `relevant_information`
   (`docs/05-data-model.md` Section 8), and most raw items never get one; a
   `signal` has its own review lifecycle and is referenced by `feedback`. Making
   these children would make it impossible to persist a raw item with no
   relevant-information, and would load/rewrite unrelated rows on every change.
2. **Every table its own aggregate, including the link tables as standalone
   aggregates.** Rejected for the link tables: a `relevant_information_area` row
   has a composite natural key, zero independent lifecycle, and no meaning apart
   from its parent — Spring Data JDBC has no clean way to treat it as a root.
3. **One aggregate per main table; the two link tables are owned children of the
   `relevant_information` aggregate; every inter-table foreign key is a plain id
   reference.** Chosen.

## Decision

- **Eight aggregate roots**, one per main table: `Source`, `Interest`,
  `AreaOfInterest`, `RawInformationItem`, `RelevantInformation`, `Signal`,
  `Feedback`, `ActivityRecord`.
- **`relevant_information_area` and `relevant_information_interest` are owned
  child collections of the `RelevantInformation` aggregate** — modelled as
  `Set<MatchedAreaRow>` / `Set<MatchedInterestRow>` via `@MappedCollection`. On
  save, Spring Data JDBC replaces the child rows for that parent; on load it
  reads them by the `relevant_information_id` back-reference.
- **Every other foreign key is a plain id** (`UUID` field), not an owned
  relationship and not a Spring Data `AggregateReference`:
  `raw_information_item.source_id`, `raw_information_item.relevant_information_id`
  (nullable), `signal.relevant_information_id`, `feedback.signal_id`,
  `activity_record.source_id` / `raw_information_item_id` (nullable).
- The contributing raw items of a `RelevantInformation` are **not** a child
  collection; they are found with
  `RawInformationItemRepository.findByRelevantInformationId(...)`.

## Reasons

- The link rows represent an **attribute** of `RelevantInformation` — "records
  which area(s) of interest and which matched interest(s) it relates to"
  (`docs/05-data-model.md` Section 9). Treating them as owned children is the
  only coherent Spring Data JDBC mapping and does not assert anything the data
  model has not already said.
- Plain id references for the remaining foreign keys is the **most conservative
  choice**: it declines to create any aggregate boundary. It matches the
  independent lifecycles the data model describes (Sections 8, 10, 12) and
  keeps each `save` scoped to a single row.
- This is an implementation mapping decision, not a change to the domain model.
  No new domain concept, relationship, or field is introduced.

## Consequences

- `RelevantInformation` is loaded and saved with its matched-area and
  matched-interest sets; replacing those sets on update is a delete-and-reinsert
  of the link rows for that parent (small sets — at most six areas).
- Navigating from a `RelevantInformation` to its contributing raw items, or from
  a `RelevantInformation` to its `Signal`, is an explicit repository call, not
  object navigation.
- If a future decision (e.g. a relevance-assessment design in Phase 5) needs a
  different boundary, only the infrastructure mapping changes; the domain types
  and ports are unaffected.

## Trade-offs

- **No cross-aggregate cascade**: deleting a `RelevantInformation` will not
  cascade to its `Signal` or raw items. This is intentional — deletion semantics
  are open (Q3) and not in scope — but it means referential cleanup, when it is
  designed, will be explicit application logic.
- **`AggregateReference` not used**: it would give a little compile-time
  type-tagging on the reference, at the cost of a Spring Data type on every
  entity and a less direct mapping. Plain `UUID` is simpler and the type safety
  is recovered in the domain layer if/when typed ids are introduced.
