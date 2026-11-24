# ADR 0018 — Pipeline reliability: provider failures, atomic indexing, reconciliation

Status: Accepted
Date: 2026-11-24
Phase: hardening of Phases 4–8 (docs/11-roadmap.md), Mission 1 — make the existing pipeline reliable

---

## Context

An audit of the existing pipeline (collect → group → process → index) found two critical
reliability defects and two weaknesses next to them:

1. **A permanent provider HTTP error was classified retryable.** Both provider adapters marked
   every HTTP error status retryable, against `docs/03-technical-spec.md` Section 13.2, which makes
   only "provider transport/5xx" retryable. A retired model (HTTP 404/410, which actually happened,
   see ADR 0006) or a revoked key (401) was retried for every item, on every sweep and on every
   restart. Items that failed with a retryable error stayed at the head of the queue (it was ordered
   by `collected_at`) and could starve every item behind them.
2. **Indexing could be lost silently.** Once relevance assessment confirms an item
   (`DEDUPLICATED → RELEVANT`, committed), indexing it into the knowledge base is best effort
   (`docs/adr/0012`, `docs/07-rag.md` Section 23). If the embedding provider failed, or the process
   stopped, between that commit and the end of indexing, the item stayed relevant but unsearchable,
   and nothing detected it or retried it ("no backfill", `docs/11-roadmap.md` Phase 8).
3. **A partial indexing was indistinguishable from a complete one.** Passages were persisted one at a
   time. A failure part-way left a mixed result, and re-indexing could not remove passages the new
   result no longer contained.
4. **Concurrent or stale indexing runs could overwrite each other.** Nothing stopped two runs from
   indexing the same content, or a slow run from overwriting a newer one.

Constraints: no new dependency, no scheduler, no broker, no new AI feature, Java owns state and
transactions, Python stays stateless, a Python call is never inside a database transaction
(`docs/03-technical-spec.md` Section 6.3), no distributed lock (`docs/08-ingestion.md` Section 15).

## Decisions

### 1. Classify provider HTTP failures, and carry the scope across the contract

The Python provider adapters classify an HTTP error status (`providers/base.py`):

| Status | Disposition | `providerFailureScope` |
|---|---|---|
| 408, 425, 429, 5xx, transport failures, timeouts | retryable | — |
| 401, 402, 403, 404, 410 | permanent, **the provider configuration was refused** (credentials, account, model or endpoint) | `configuration` |
| any other 4xx (400, 409, 413, 422…) | permanent, **looks specific to this request** — see Section 2 | `request` |

The error envelope keeps the code `AI_PROVIDER_UNAVAILABLE` and category `provider`, sets
`retryable = false` when permanent, and adds `details = { providerStatus, providerFailureScope }`.
The JSON schema does not change (`retryable` and `details` already exist; ADR 0006: Java is driven by
`category` and `retryable`). A new example, `response.error.provider-rejected.json`, pins it on both
sides. This aligns the code with spec Section 13.2; the spec is unchanged apart from one clarifying
sentence.

### 2. Suspend a capability whose configuration was refused — durably

A permanent `FAILED` is only right for an item that is itself the problem (non-retryable, spec
Section 13.2), and there is no way to bring a `FAILED` item back (manual reprocessing, Q25, is still
open). So a rejection must not mark items `FAILED` while its cause is uncertain.

- A **configuration refusal** (401/402/403/404/410) affects every request the same way. The backend
  **suspends the capability** instead of failing items.
- A **rejection that looks specific to one request** (400, 409, 413, 422…) is ambiguous. It is what
  one oversized item gets (a context that is too long is a 400), and it is also what *every* item
  gets when a model parameter is not accepted. The Python side cannot tell them apart; the backend
  can, from the capability's history. If the capability **succeeded within `evidence-window`** (24h,
  recorded in `ai_capability_health`), the configuration is known to work, so the rejection is that
  request's own fault: the failure is returned as it is and the item is marked `FAILED`, as spec
  Section 13.2 says. **Without that evidence the cause is uncertain**: the capability is suspended
  and the item preserved. A probe that is rejected this way does not count as evidence either.
- This is bounded, not a retry loop. A suspended capability is probed once per cooldown, and the
  cooldown doubles up to 6 h. A genuinely bad item is rejected for good as soon as another item has
  shown that the capability works, so it cannot block the queue: the item that could not be handled
  is moved behind the others (Section 3: only the item whose request made the capability suspect —
  the failure carries `rejectedThisRequest` — not the items that were merely skipped), so a
  *different* item probes first. A global mistake keeps
  the capability suspended with one call per cooldown, and no item is failed.

The mechanism:

- `SuspendingAiCapabilityInvoker` decorates the existing `AiCapabilityInvoker` (no new port). On a
  configuration refusal it persists a suspension (`ai_capability_suspension`, migration V12) and
  returns a synthesised failure `AI_CAPABILITY_SUSPENDED` (category `suspended`).
- While suspended the capability is **not called**. The suspension is persisted, so a **restart does
  not retry**.
- When the cooldown ends, exactly one caller **probes** (the row is claimed by one conditional
  `UPDATE`). A successful probe clears the suspension; a refused probe suspends again for a longer
  cooldown (exponential, capped: `signal-engine.ai.suspension.initial-cooldown` 15m,
  `max-cooldown` 6h, `probe-lease` 6m, `evidence-window` 24h — provisional, T9).
- A pipeline stage that gets a suspended failure leaves the item **untouched**, moves it behind the
  others, and **carries on with the rest of the sweep**. Suspension is per capability and the
  capabilities are independent (relevance, importance and summarisation are three; indexing needs
  `embed` and possibly `semantic-chunk-boundary`; near-duplicate assessment is only called when a
  candidate exists), so an item that needs another capability, or none, still progresses. A call to
  a suspended capability costs one lookup, not a network call. One Activity Record per sweep
  summarises how many items wait. Nothing is marked `FAILED`; nothing is lost.
- Visibility: the Activity Record, the `ai_capability_suspension` row (reason, provider status,
  until), and a WARN log. **Manual recovery** after fixing the configuration, without waiting for the
  cooldown: `DELETE FROM ai_capability_suspension WHERE capability = '<capability>'`. No endpoint is
  added: manual operations are an open decision (Q8, Q25, T17).
- The suspension is per **capability** (the unit Java knows: `relevance`, `embed`, …). Capabilities
  that share a provider each learn of the refusal with one call.

### 3. Do not let a failing item starve the queue

A retryable failure, or a suspended capability, leaves the item in its state but records the attempt
(`RawInformationItemRepository.recordRetryableAttempt`, which only moves `processing_updated_at`),
and pending pages are ordered by that time instead of `collected_at`. An item that keeps failing
transiently goes behind the items not attempted yet. No schema change.

### 4. Index atomically: claim, prepare outside the transaction, replace in one

`IndexedPassageStore` (the generic RAG write port) changes from "save a passage" to **claim, prepare,
replace**:

1. `claim(contentId, contentFingerprint, chunker)` takes the exclusive right to index the content.
   It is denied while another run holds an unexpired claim, and when the content is already
   completely indexed for this fingerprint and chunker configuration.
2. The pipeline chunks and embeds **outside any transaction** (remote calls).
3. `replaceContent(claim, fingerprint, passages)` stores everything in **one PostgreSQL
   transaction**: upsert of every passage and embedding, deletion of the passages of the **same
   content and chunker configuration** that the new result no longer contains, a verification that
   what is stored matches what was written, and the completion marker. Anything failing rolls the
   whole thing back, so a reader sees the previous complete index or the new one, never a mixture.
4. `abandon(claim, countAsFailedAttempt)` releases a claim after a failure.

`save` is removed from the port: two write paths, one of them non-atomic, would defeat the guarantee.

**No passage is a failed indexing.** If chunking produces no passage there is nothing to mark as
indexed, so the pipeline throws instead of reporting success: the failure is counted against the
content (bounded by `max-failed-attempts`) and `IndexingReport.fullyIndexed()` requires at least one
passage. An indexing is never announced as successful unless a completion marker was persisted.
Per-passage failure tallying (ADR 0012 trade-offs) disappears with it; the operation is all or nothing.

### 5. An explicit completion marker, valid only if it matches

`rag_content_index` (migration V13) holds one row per content ever indexed (id = the raw information
item id, as in `rag_passage.content_id`; no business foreign key, so the RAG tables stay generic). The
marker columns (`indexed_at`, `content_fingerprint`, `chunker_id`, `chunker_version`, the embedding
model, `passage_count`) are written **only inside the replacement transaction** and are all-or-nothing
(a CHECK constraint). They state what the indexing was built from, so a content is **complete and
current only when a marker exists and still matches** the item's content hash, and the configured
chunker. No marker means absent, partial or never finished: **never assumed complete**.

The migration marks nothing as indexed. Items indexed before markers existed are therefore
reconciled once (re-chunked and re-embedded; the atomic replacement also removes any stale passage a
partial legacy indexing left).

*Not covered:* a change of the **embedding model** is not detected by the marker alone (the model is
only known after an `embed` call). That belongs to the still-open re-embedding procedure (T3,
`docs/07-rag.md` Section 23.4).

### 6. Claim, lease and fencing token

`rag_content_index.revision` grows with every claim and is a **fencing token**: `replaceContent` locks
the row (`SELECT … FOR UPDATE`) and is rejected with `StaleIndexClaimException` — nothing written — if
the revision is no longer the claim's. A run whose lease expired and was taken over therefore **cannot
overwrite a newer indexing**, whatever the lease length. The lease (`signal-engine.rag.indexing
.claim-lease`, 15m) only trades how fast a crashed run is recovered against how often a slow run is
superseded; correctness does not depend on it. A superseded run is reported as *skipped*, not as an
error. This is a per-content, in-database claim, not a distributed lock and not a scheduler lock.

### 7. Reconcile the knowledge base index

`ReconcileKnowledgeBaseIndexUseCase` indexes one bounded page of items whose relevance is confirmed
and that are not completely and currently indexed (no matching marker, no unexpired claim, fewer than
`max-failed-attempts` failures). It reuses the existing `KnowledgeBaseIndexer` port, so the indexing
that follows relevance assessment and the reconciliation are the same operation. The port also tells
which items wait (`findItemIdsAwaitingIndexing`): whether an item is indexed completely and currently
is knowledge of the knowledge base, so the query lives in `KnowledgeBaseIndexBacklog`, next to the
bridge `SignalEngineIndexableContent` that already maps a raw information item to indexable content,
and the ingestion repository knows nothing about the RAG tables. It reads the processing state
(which states mean "relevance confirmed") and the content hash from `raw_information_item`; that
knowledge sits at the bridge on purpose. `StartupIngestionRunner`
runs it as the last stage — still once per process start, **not a scheduler**. A content that keeps
failing is attempted `max-failed-attempts` times (default 5), then left alone; a suspended capability
does not count as an attempt, and does not stop the sweep (Section 2).

### 8. Do not show two chunker configurations of one content to retrieval

ADR 0012 lets passages of different chunker configurations coexist in storage. Retrieval did not know
which one is current, so a re-indexing with another configuration could return both. `PgVectorRetriever`
now returns, for a content with a completion marker, only the passages of the marker's chunker
configuration. A content with no marker (legacy, or never completed) or whose re-indexing is in
progress stays searchable as it was. Nothing is deleted.

## Options considered

- **Mark every permanent provider error `FAILED`.** Rejected: a revoked key would mark the whole
  backlog `FAILED`, with no recovery path (Q25 open).
- **A new `AI_PROVIDER_REJECTED` error code.** Rejected: it changes the contract for no behavioural
  gain; `retryable` and `details` carry the information.
- **Suspend per provider.** Rejected: Java does not know which provider a capability uses, and must
  not (CLAUDE.md Section 13).
- **Marker columns on `raw_information_item`.** Rejected: it mixes a RAG concern into the Processing
  State and cannot be written in the same transaction as the replacement without coupling the generic
  store to the ingestion table.
- **Treat "has at least one passage" as indexed.** Rejected: it cannot detect a partial indexing.
- **Spring Batch, ShedLock, an outbox.** Not needed: the pipeline is already an idempotent persisted
  state machine on a single instance (spec Section 25).

## Consequences

- Migrations V12 (`ai_capability_suspension`, `ai_capability_health`) and V13
  (`rag_content_index`). No new dependency.
- Contract: the `AI_PROVIDER_UNAVAILABLE` error may now be non-retryable and carries `details`; new
  example fixture; `ai-capability.v1.schema.json` unchanged.
- `IndexedPassageStore`: `save` and `PassagePersistOutcome` removed; `claim`, `replaceContent`,
  `abandon` added. `StagedIndexingPipeline` reports an already-current or claimed content as skipped.
- `RawInformationItemRepository`: `recordRetryableAttempt`. `KnowledgeBaseIndexer` gains
  `findItemIdsAwaitingIndexing`. Neither the repository port nor its adapter references the RAG
  schema or any RAG type.
- ADR 0006 (error mapping) and ADR 0012 (idempotency, "nothing is silently deleted", per-passage
  tally) carry an update note pointing here.

## Trade-offs

- **Reconciling the existing corpus re-runs the chunker and the embedding model once per item** (the
  semantic chunker makes LLM calls). It is bounded by the sweep's batch size and is the only safe
  alternative to assuming old indexings are complete.
- **Immediate recovery after a configuration fix needs the cooldown to end or a manual `DELETE`.**
  The first probe after the cooldown resumes the pipeline by itself.
- **A content that fails permanently uses up `max-failed-attempts` attempts** before it is left alone;
  the bound is simple, not clever. There is no manual re-queue yet (Q25).
- **Retrieval joins `rag_content_index`.** One indexed lookup per candidate row; negligible at the
  MVP scale (exact scan, T7 open).
- **Known limit — recovery after a crash needs a later sweep (M3).** An item whose indexing was
  interrupted keeps its claim until the lease (15 min) expires, and reconciliation skips items with a
  live claim. Sweeps happen only at process start, so after a crash and a quick restart the item is
  picked up at the *next* start after the lease has expired, not before. Nothing is lost; it is
  delayed until something runs the sweep again. A scheduler (Q9) removes the limit.
- **Known limit — reconciliation throughput (M4).** One sweep indexes at most
  `reconciliation-batch-limit` items (default 20). The existing corpus, which has no completion
  marker, is therefore reconciled over `N / 20` process starts, each re-running the chunker (LLM
  calls with the semantic chunker) and the embedding model. Until an item is reconciled it stays
  searchable as before. Raise the limit to catch up faster at the cost of a longer startup run.
- **A request rejected before the capability ever worked stalls it for one cooldown.** A
  genuinely oversized item that happens to be the first request after a quiet day (no success within
  24h) suspends the capability for 15 minutes, until a different item probes it. That is the price
  of never failing a whole batch for a global mistake. The window and cooldowns are configurable.
- **The item-level retry budget of Section 13.3 (3 attempts, backoff) is still not implemented**:
  transient failures are retried once per sweep, indefinitely. Sweeps happen only at startup today;
  the budget belongs with the scheduler (Q9, Q10).

## Explicitly still open (not touched here)

Q8/Q9/Q10 (manual collect, cadence, source retry), Q19/Q20 (alerts), Q25/T17 (manual reprocessing),
T3 (re-embedding procedure), T7 (ANN index), T9 (all numeric defaults above are provisional).
