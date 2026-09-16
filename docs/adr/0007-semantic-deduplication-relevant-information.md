# ADR 0007 — Semantic deduplication & Relevant Information

Status: Accepted
Date: 2026-09-06
Phase: 2 (docs/11-roadmap.md), Task 6B — Semantic Deduplication / Relevant Information

---

## Context

Ingestion (Task 5) persists each collected item as a Raw Information Item past *exact*
deduplication. Task 6B adds the next step: decide whether a normalised item reports
the **same underlying story** as information already known, and either attach it to
that existing **Relevant Information** record or create a new one — so several
sources reporting one story produce one grouped record, with every contributing
item's provenance retained (`docs/02-functional-spec.md` Section 7.4;
`docs/05-data-model.md` Section 18; `docs/11-roadmap.md` Phase 5).

Constraints from the specs and the task:

- **AI-assisted, but never O(n²).** The AI must not compare a new item against all
  history. A bounded candidate set is selected first (`docs/06-ai-agents.md`
  Section 4.1).
- **Java owns the decision.** Python returns an advisory judgement; Java owns the
  candidate set, the grouping decision, the processing-state transition, and
  persistence (`docs/03-technical-spec.md` Section 3.3, 10.5;
  `docs/04-architecture.md` Section 8.2).
- **The near-duplicate threshold (Q12 / T15) stays open**, as does the embedding
  model/dimension (T3) and pgvector index (T7). This task uses none of them.
- Reuse the Task 6A AI foundation; do not build a second one.

## Decisions

### The AI capability: `near-duplicate` v1 (a boolean judgement, no score)

`POST /capabilities/near-duplicate/v1` (envelope per ADR 0006).

- **Payload:** `{ "candidate": { "text": "…" }, "comparisons": [ { "id": "…",
  "text": "…" } ] }` — the item to place, and the bounded set Java chose (1–25).
- **Result:** `{ "assessments": [ { "id": "…", "sameUnderlyingStory": <bool>,
  "reason": "<one sentence>" } ] }` — exactly one verdict per comparison id.
- The capability validates the id-coverage rule inside the **one bounded repair**
  loop (a missing or extra id triggers the repair; a second failure is
  `AI_OUTPUT_INVALID`). It ranks nothing, scores nothing, groups nothing, and
  writes nothing.
- Prompt asset `agents/prompts/near-duplicate/v1/{system,user}.txt`. Reported
  version `near-duplicate/v1`. The prompt states that all candidate/comparison
  text is untrusted data.

**Why a boolean, not a similarity score + threshold.** `docs/06-ai-agents.md`
Section 4.1 describes a *score* whose threshold comparison is Java's — but that
threshold is **Q12 / T15, explicitly open**, and the task forbids inventing it. A
per-comparison boolean `sameUnderlyingStory` is a well-formed "similarity signal
usable in a near-duplicate decision" (Section 4.1, item 2); Java's decision then
needs no numeric cutoff, so Q12 / T15 genuinely stays open. The numeric-score
mechanism (which is also the natural place embeddings would enter) is deferred with
T3 / T7 / T15.

### Candidate selection: recency-bounded, no embeddings

`RawInformationItemRepository.findMostRecentlyGrouped(limit)` returns the `limit`
most recently collected items that are already associated with a Relevant
Information record (`WHERE relevant_information_id IS NOT NULL ORDER BY collected_at
DESC LIMIT :limit`). `limit` = `signal-engine.near-duplicate.candidate-pool-size`
(default **10**).

This is the simplest bounded strategy compatible with the current schema: it turns
"compare against all history" into "compare against a fixed *k*", so one item costs
**at most one AI call with ≤ k comparisons**, regardless of how much history exists.
No ranking, no reranking, no search engine, no vector store, no new index.

Embedding-similarity (ANN) candidate selection — the approach `docs/06-ai-agents.md`
Section 4.1 and `docs/08-ingestion.md` Section 9 anticipate — is **not** used because
it would require choosing an embedding model/dimension (T3) and a pgvector index
(T7), both deferred. When those are decided, `findMostRecentlyGrouped` is the seam
to replace.

**Known limitation, documented not fixed:** a true near-duplicate older than the
*k* most recent grouped items is missed and the new item starts its own Relevant
Information record. Acceptable for the curated-source MVP; a larger window, a
lexical pre-filter, or embedding ANN are all future refinements. A `collected_at`
index is a tuning decision (not added — no schema change this task).

### The grouping flow (`GroupIntoRelevantInformationUseCase`)

Per item (`groupRawInformationItem(id)`), and as a bounded batch
(`groupNormalizedRawInformationItems()` over `findByProcessingState("normalized",
batch-limit)`, default **50**):

1. Load the item; if its state is not `normalized`, return
   `SKIPPED_ALREADY_ASSESSED` (idempotent no-op).
2. Select the bounded candidate pool. If it is empty (or the item has no normalised
   text), skip the AI and create a new Relevant Information record.
3. Ask `NearDuplicateAssessor` (which calls the capability through the Task 6A
   `AiCapabilityInvoker`).
4. **Java decides:**
   - the first candidate the AI marked `sameUnderlyingStory = true` → **associate**
     the item with that candidate's Relevant Information record, state → `duplicate`;
   - otherwise → **create** a new Relevant Information record, associate the item,
     state → `deduplicated`.
5. Record a `near_duplicate_assessment` Activity Record (success or failure).

The new Relevant Information record is created **empty** — `reason = null`, no
matched areas or interests. Those are populated later by relevance assessment
(Task 7). See "Documentation reconciliation" below.

**Multiple matches across different records** (the AI marks candidates from two
different Relevant Information records as the same story): Java attaches to the
**first** in the recency-ordered pool. Relevant Information records are never merged
— that would be a cross-record identity mechanism, out of scope. Documented as a
deliberate simplification.

### Java architecture

- `application/dedup/` — `GroupIntoRelevantInformationUseCase` (input port),
  `NearDuplicateAssessor` (output port over the AI), `GroupingReport`, and plain
  `Default*` implementations. Depends only on domain types and application ports
  (the repositories, `UnitOfWork`, `AiCapabilityInvoker`). No Spring, HTTP, JDBC,
  or SQL.
- `DefaultNearDuplicateAssessor` calls `AiCapabilityInvoker.invoke("near-duplicate",
  1, payload, …)`; a result that is not exactly one verdict per candidate, or a
  typed AI failure, becomes `AssessmentUnavailable(AiError)` — never acted on as a
  grouping.
- `infrastructure/dedup/` — only a composition-root `@Configuration` and its
  `@ConfigurationProperties`. No adapter of its own; the AI call reuses
  `infrastructure/ai` (ADR 0006).

### Processing-state change (smallest coherent)

`docs/03-technical-spec.md` Section 10.3's illustrative sequence is `received →
normalized → (duplicate | deduplicated) → classified → …`. Task 5 provisionally used
`deduplicated` for a freshly-collected, exact-deduped item. Task 6B **retargets**:

| State | Meaning (Task 6B onward) | Set by |
|---|---|---|
| `normalized` | collected, normalised, past *exact* dedup; awaiting semantic near-duplicate assessment | ingestion (Task 5) |
| `duplicate` | a semantic near-duplicate of existing Relevant Information; attached as a corroborating item | Task 6B |
| `deduplicated` | semantically distinct; anchors its own Relevant Information record — deduplication complete | Task 6B |
| `failed{stage, reason, retryable}` | a stage failed | any stage |

Every string is from the Section 10.3 illustrative sequence — no new vocabulary is
invented, and the two outcomes of the dedup step are now exactly `duplicate` and
`deduplicated`. `ProcessingState` gains `normalized(at)` and `duplicate(at)`
factories; Task 5's one line and three test assertions change from `deduplicated`
to `normalized`. `docs/adr/0005` carries a pointer to this. The state vocabulary
remains formally open (`docs/05-data-model.md` Section 14).

### Idempotency and concurrency

- **State gate:** only `normalized` items are processed; a re-run of an
  already-grouped item is `SKIPPED_ALREADY_ASSESSED`.
- **Conditional transition:** `RawInformationItemRepository.transitionFromState(id,
  "normalized", newState, relevantInformationId)` is a `@Modifying` update with
  `WHERE id = :id AND processing_state = 'normalized'` that returns whether a row
  changed. Two concurrent runs cannot both group one item — the loser's update
  affects 0 rows.
- **No orphan Relevant Information from a race:** the create-new path runs inside
  the existing `UnitOfWork`; if the conditional transition affects 0 rows, it throws
  and the transaction rolls the just-created record back.
- No distributed lock, no new `@Version` column, no schema change. This uses the
  existing `processing_state` column as the guard.

### Failure handling

`AssessmentUnavailable` from the assessor (a typed AI error from ADR 0006, or a
contract violation synthesised by the assessor):

- **retryable** (transport, timeout, provider) → the item is left `normalized`; the
  next batch run retries it. An Activity Record records the failure.
- **non-retryable** (`AI_OUTPUT_INVALID`, `AI_CONTRACT_VIOLATION`) → the item is
  moved to `failed{stage = "near_duplicate_assessment"}` via the conditional
  transition; manual reprocessing (T17) is deferred.

A failed assessment never creates or modifies a Relevant Information record. Batch
processing isolates an unexpected `RuntimeException` per item and continues
(`docs/03-technical-spec.md` Section 13.6, 13.8). No new retry framework — this
follows the ADR 0006 error contract.

## Consequences

- New application package `application/dedup/`; one new AI capability
  (`near-duplicate`) reusing the whole Task 6A foundation (provider, prompt loader,
  repair, envelope, contract test).
- `RawInformationItemRepository` gains `findByProcessingState`,
  `findMostRecentlyGrouped`, and `transitionFromState`; the Spring Data JDBC crud
  repository gains three `@Query`s (one `@Modifying`). **No schema change, no
  migration.**
- `ProcessingState` gains `NORMALIZED` / `DUPLICATE` constants and factories;
  Task 5's terminal state moves `deduplicated → normalized`.
- New config namespace `signal-engine.near-duplicate.*` (two ints, provisional).
- A distinct normalised item now always has a Relevant Information record — see
  reconciliation.
- Task 7 (relevance/importance) picks up from `deduplicated` items and enriches
  their Relevant Information records.

## Documentation reconciliation

`docs/05-data-model.md` Section 9 previously read "Relevant Information is the
retained result of relevance assessment." The roadmap Phase 5 pipeline
(`Normalized Raw Information → Near-Duplicate Assessment → Relevant Information`,
then "classification and relevance assessment are integrated") and the task's
explicit instruction ("distinct information may create a new Relevant Information"
in the near-duplicate step) place record *creation* earlier. Per `CLAUDE.md`
Section 27, the current explicit decision wins and Section 9 is updated: a Relevant
Information record is **created as a grouping container at near-duplicate
assessment** (this task) and **enriched with the relevance verdict, matched
areas/interests, and a user-visible reason at relevance assessment** (Task 7). This
is a lifecycle clarification, not a new concept.

## Trade-offs

- **Recency-bounded candidate selection** misses older near-duplicates. Accepted:
  simplest bounded strategy, no deferred decision touched, honest limitation.
- **Boolean AI verdict, no score** deviates from `docs/06-ai-agents.md` Section 4.1's
  wording. Accepted and documented: it keeps Q12 / T15 open, which the task requires.
- **Empty Relevant Information records before relevance** — a distinct item gets a
  record with only its contributing-item link. Accepted: matches the roadmap
  pipeline; Task 7 fills the rest; the alternative (a second grouping structure)
  would duplicate what Relevant Information already is.
- **First-match tie-break across records; no merge.** Accepted: merging records is a
  cross-record identity mechanism the task excludes.
- **Conditional `@Modifying` update** is more than `save()` but is the smallest
  correct concurrency guard using an existing column.

## Explicitly still open (untouched)

T3 (embedding model/dimension), T7 (pgvector index), **Q12 / T15 (near-duplicate
threshold)**, Q4 / T16 (Signal decision), Q14 (summary format), Q19 / Q20 (alerts),
Q21 (Q&A), multilingual behaviour. This task creates no Signal, assesses no
importance or relevance, writes no summary, and raises no alert.
