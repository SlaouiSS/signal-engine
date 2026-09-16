# ADR 0008 — Relevance → Importance → Signal → Summary

Status: Accepted
Date: 2026-09-07
Phase: 2 (docs/11-roadmap.md), Task 7 — the relevance/importance/Signal/Summary pipeline

---

## Context

Task 6B leaves a semantically-distinct item as an empty **Relevant Information**
record. Task 7 turns those into **Signals** with **Summaries**, following the
product flow (`docs/01-product-spec.md`; `docs/11-roadmap.md` Phase 6):

```
Relevant Information → relevance assessment → (noise | relevant)
                     → importance assessment (only if relevant) → (no signal | signal)
                     → deterministic Signal creation → source-grounded Summary
```

Constraints from the specs and the task:

- **Relevance ≠ importance** — two separate AI capabilities and two separate Java
  decisions (`docs/06-ai-agents.md` Section 4.3–4.4).
- **AI is advisory; Java owns every business-state transition**
  (`docs/03-technical-spec.md` Section 3.3; `docs/04-architecture.md` Section 8.2).
- **The signal-selection criteria (Q4 / T16) stay open.** So does the summary
  form/length (**Q14**). Neither may be invented.
- The summary must be **strictly source-grounded** — no invented facts, no advice
  (`docs/02-functional-spec.md` Section 8.2, R4, R25).
- Reuse the Task 6A AI foundation; do not build a second one.
- No alerts, embeddings, RAG, search, Q&A, frontend, or scheduler.

## Decisions

### Three AI capabilities, each a bounded boolean-plus-reason (no scores)

| Capability | v | Payload | Result |
|---|---|---|---|
| `relevance` | 1 | item text, the six-area catalogue, the user's enabled interests | `relevant` (bool), `reason`, `matchedAreaCodes`, `matchedInterestIds` |
| `importance` | 1 | item text, relevance context (reason + matched area codes) | `importantEnough` (bool), `reason` |
| `summarize` | 1 | source content, source references (name + url) | `summary`, `groundingNotes` |

Each returns exactly the decision required, no numeric score, ranking, or
taxonomy — the same "boolean + reason" shape Task 6B used for near-duplicate, and
the reason exactly what `docs/02-functional-spec.md` Section 9.2 requires the user
to see. `relevance` also does the **area/interest matching directly** — Task 7
ships **no separate `classify` capability** (the task lists three capabilities;
`docs/06-ai-agents.md` Section 4.2 item 10 permits folding classification into
relevance). Java-side validation rejects a matched code/id the query did not
supply, and a "relevant"/"important" verdict with no reason (or no matched area)
— as a non-retryable `AI_CONTRACT_VIOLATION`, never acted on.

Each capability: stateless, provider-independent (only the `LlmProvider` Protocol),
no database, its own versioned prompt asset (`agents/prompts/<name>/v1/`), the
Task 6A structured-output + **one bounded repair** (relevance and importance push
their cross-field rule into that same repair loop). All supplied content is
treated as untrusted data by the prompts.

**Why boolean, not score + threshold.** `docs/06-ai-agents.md` Section 4.4 calls
the signal-selection criteria "the most important open point" (Q4 / T16). A
per-decision boolean keeps Java's rule trivial ("`importantEnough` → create a
Signal") and invents no criterion, so Q4 / T16 genuinely stays open. A future,
more sophisticated importance capability replaces this one behind the same port.

### Java ports and the one orchestrating use case

- `application/signal/` — `ProcessRelevantInformationUseCase` (input port:
  `process(UUID)` and the bounded batch `processPending()`), three focused output
  ports (`RelevanceAssessor`, `ImportanceAssessor`, `SummaryGenerator`), and plain
  `Default*` implementations. Depends only on domain types and application ports.
- `Default{Relevance,Importance,Summary}*` call their capability through the Task
  6A `AiCapabilityInvoker`; a bad result or a typed AI failure becomes
  `AssessmentUnavailable(AiError)` / `GenerationUnavailable(AiError)` — never acted
  on as a business outcome.
- `DefaultProcessRelevantInformationUseCase` drives the full pipeline in one call:
  relevance → (relevant?) importance → (important?) Signal + Summary. Each step
  runs its conditional state transition and the writes it guards in **one
  `UnitOfWork`**; the loop re-reads the anchor after each and continues.
- `infrastructure/signal/` — a composition-root `@Configuration` and its
  `@ConfigurationProperties` (`signal-engine.signal-pipeline.batch-limit`, default
  50, provisional). No adapter of its own; the AI calls reuse `infrastructure/ai`.

### The pipeline position is tracked on the record's "anchor" raw item

A Relevant Information record has exactly one contributing raw item that is not a
`duplicate` (Task 6B) — its **anchor**. Task 7 moves that anchor's processing
state; `duplicate` near-duplicates are untouched. New states, all from the
`docs/03-technical-spec.md` Section 10.3 illustrative sequence:

| State | Meaning | Set when |
|---|---|---|
| `relevant` | relevance found it relevant; awaiting importance | relevance = relevant |
| `not_relevant` | set aside as noise (terminal) | relevance = not relevant |
| `signal_created` | a Signal exists; the Summary is pending / being retried | importance = important |
| `no_signal` | relevant but not important enough (terminal) | importance = not important |
| `summarized` | the Signal has a source-grounded Summary (terminal for Task 7) | summary generated |

`ProcessingState` gains the matching factories. No workflow engine, no state
machine class — it stays a free-text value object, vocabulary still formally open
(`docs/05-data-model.md` Section 14).

### Relevant Information enrichment

For a **relevant** verdict, `relevantInformationRepository.save(ri.withRelevance(
reason, matchedAreaCodes, matchedInterestIds))` fills the `reason` and replaces the
matched-area/interest child rows (the aggregate boundary from ADR 0002). For a
**not relevant** verdict the `reason` is set and the matched sets left empty; the
record is retained (set aside as noise, `docs/02-functional-spec.md` Section 7.5),
not deleted.

### Signal

Created deterministically from the importance verdict: `new Signal(null, riId,
NEW, …)` — exactly the existing V7 model (one signal per record via the
`relevant_information_id` unique constraint; states `NEW → REVIEWED → KEPT /
DISMISSED`). No new column, no signal kind/score. Feedback compatibility is
unchanged — `RELEVANT` / `NOT_RELEVANT` against a Signal (V8), untouched.

### Summary — new table (V10), the one migration

There was no summary table before Task 7. `summary` holds `id`, `signal_id`
(unique FK), `summary_text`, `grounding_notes`, timestamps. **Deliberately no
`status` column**: "pending" is the absence of a row; a failed generation is
recorded in `activity_record` and the anchor's processing state, then retried. No
model/prompt-version columns (that is later evaluation/observability work).
Whether a summary can be regenerated is not decided (`docs/05-data-model.md`
Section 11).

**Q14 (summary form/length) stays open.** The provisional guidance —
"at most 4 sentences" in the prompt, a 1 500-character schema guard against
runaway output — is marked provisional in the prompt, the capability, the
migration comment, and `docs/05-data-model.md` Section 11.

### Summary input — only what the decision needs

The `summarize` request carries the **anchor's normalised content** (the
representative text of the record — near-duplicates report the same story) plus up
to 10 `(source name, url)` references for provenance context. No database dump; the
capability has no database or web access.

### Idempotency and concurrency

- **State gate:** each step processes the anchor only from its expected state; a
  fully-processed / set-aside / no-signal record is `SKIPPED`.
- **Conditional transition:** every step uses `transitionFromState(anchorId,
  expectedState, newState, riId)` (the Task 6B `@Modifying` guard, extended to
  carry the existing record id through Task 7 transitions including failures). Two
  concurrent runs cannot both advance one anchor — the loser's update hits 0 rows.
- **Atomic step:** the transition and the writes it guards (enrich the record /
  create the Signal / save the Summary) run in one `UnitOfWork`; a partial failure
  rolls back. The Signal `relevant_information_id` unique and the Summary
  `signal_id` unique constraints are the final backstop.
- No distributed lock, no new `@Version` column.

### Failure handling (aligned with the ADR 0006 error contract)

Any `AssessmentUnavailable` / `GenerationUnavailable`:

- **retryable** (transport, timeout, provider) → the anchor is left in place; the
  next batch run retries it. For a summary failure the Signal already exists and
  is kept (`docs/06-ai-agents.md` Section 4.5 item 9) — outcome
  `SIGNAL_SUMMARY_PENDING`.
- **non-retryable** (`AI_OUTPUT_INVALID`, `AI_CONTRACT_VIOLATION`) → the anchor is
  moved to `failed{stage}` via the conditional transition; the Signal (if any) is
  kept; manual reprocessing (T17) is deferred.

Every outcome writes a `signal_pipeline` `ActivityRecord`. A failed assessment
**never** creates or enriches a Relevant Information record, Signal, or Summary.
No new retry framework.

## Consequences

- New application package `application/signal/`; three capabilities reusing the
  whole Task 6A foundation (envelope, provider, prompt loader, repair, contract
  test).
- `RawInformationItemRepository` gains `findByProcessingStateIn`; the crud
  repository gains one `@Query`. `SummaryRepository` + its Spring Data JDBC
  adapter are new.
- `ProcessingState` gains five states + factories.
- **One migration, V10 (`summary`).** No change to any existing table.
- `RelevantInformation` gains a `withRelevance(...)` copy helper.
- New config namespace `signal-engine.signal-pipeline.*` (one int, provisional).
- `docs/05-data-model.md` Section 11 marks the summary-length guidance provisional
  and points here.
- Task 8 (knowledge base / embeddings) picks up from `summarized` records.

## Trade-offs

- **Boolean verdicts, no scores.** Deviates from `docs/06-ai-agents.md`
  Section 4.3–4.4's "optional degree" / "uncertainty indicator". Accepted and
  documented: it keeps Q4 / T16 and the degree question open, which the task
  requires; a richer capability slots in behind the same port.
- **No `classify` capability.** Relevance does area matching directly. Accepted
  (permitted by Section 4.2 item 10; the task lists three capabilities). A separate
  `classify` can be added later without changing the relevance contract from Java's
  side.
- **Anchor content only for the summary.** A multi-source record's near-duplicate
  texts are not concatenated into the summary input. Accepted: they report the same
  story; sending all of them is a larger prompt for no decision benefit. The
  contributing sources are still listed for provenance.
- **One large orchestrating use case.** ~300 lines driving four steps. Accepted:
  it is one coherent pipeline stage (as `DefaultCollectFromSourceUseCase` and
  `DefaultGroupIntoRelevantInformationUseCase` are), split into one private method
  per step, each testable by setting the anchor's state.
- **Empty `status` on `summary`.** "Pending" is row-absence. Accepted: the
  smallest schema; the processing state and activity records carry the pending /
  failed detail.

## Explicitly still open (untouched)

**Q4 / T16** (signal-selection criteria), **Q14** (summary form/length — provisional
only), T3 (embedding model), T7 (pgvector index), Q12 / T15 (near-duplicate
threshold), Q13 (browsing not-relevant items), Q15 (signal lifecycle detail),
Q18 (feedback change/comments), Q19 / Q20 (alerts), Q21 (Q&A), the future
evaluation subsystem, and multilingual behaviour. Task 7 creates no alert, no
embedding, no search or Q&A, and no frontend change.
