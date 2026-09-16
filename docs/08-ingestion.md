# Signal Engine — Ingestion Architecture

Document ID: `08-ingestion.md`
Status: Draft — awaiting review
Depends on: `CLAUDE.md`, `docs/01-product-spec.md`, `docs/02-functional-spec.md`,
`docs/03-technical-spec.md`, `docs/04-architecture.md`, `docs/05-data-model.md`,
`docs/06-ai-agents.md`, `docs/07-rag.md`

---

## 1. Purpose and Scope

Ingestion is responsible for moving information from configured, reliable
sources into the Signal Engine knowledge base, and — where it qualifies — into
a Signal. This document exists because `docs/03-technical-spec.md`
Section 1.3 explicitly defers "concrete source connectors and parsing rules"
to this document, and because `docs/03-technical-spec.md` Section 10 and
`docs/04-architecture.md` Section 7 both describe the ingestion pipeline only
at a technical/architectural level, leaving the connector-level and
stage-level detail to be worked out here.

This document covers:

- source configuration as the **input** to ingestion (Section 3);
- collection (Section 5);
- parsing/extraction (Section 6);
- normalization (Section 7);
- deduplication, exact and semantic (Sections 8–9);
- the handoff to AI processing (Sections 10–11);
- persistence, processing state, and idempotency (Sections 13–15);
- retries, failures, and visibility (Sections 17–19);
- provenance throughout (Section 13);
- how ingestion connects to the RAG layer (Section 22).

This document does **not** redefine:

- product behavior (`docs/01-product-spec.md`);
- functional requirements (`docs/02-functional-spec.md`);
- the overall technical stack (`docs/03-technical-spec.md`);
- the system architecture (`docs/04-architecture.md`);
- the data model (`docs/05-data-model.md`);
- the AI capabilities (`docs/06-ai-agents.md`);
- the RAG architecture (`docs/07-rag.md`).

Where this document needs one of those documents' content, it references it
rather than restating it. It does not resolve any open product, functional, or
technical question — open points are named and preserved (Section 26).

---

## 2. Ingestion Principles

Already-approved principles, applied specifically to ingestion. No additional
principle is introduced.

- **Source-driven.** Only configured, curated sources are collected in the
  MVP — not unrestricted or open-ended Internet monitoring
  (`docs/02-functional-spec.md` Section 1; `docs/03-technical-spec.md`
  Section 10.2).
- **Plug-and-play.** Sources must be addable, removable, replaceable,
  enabled, disabled, and editable without changing unrelated ingestion or
  business logic (`docs/02-functional-spec.md` R22).
- **Deterministic before AI.** Every stage that can be computed by a rule runs
  deterministically before any AI capability is invoked
  (`docs/03-technical-spec.md` Section 3.3).
- **Idempotent.** Running the same collection or processing stage more than
  once must not create unintended duplicate business records
  (`docs/03-technical-spec.md` Section 10.4).
- **Provenance-first.** Every collected item remains traceable to its source
  throughout every stage (`docs/01-product-spec.md` Section 3;
  `docs/05-data-model.md` Section 15).
- **Explicit processing state.** Progress and failure are represented
  explicitly, never inferred (`docs/03-technical-spec.md` Section 3.5, 10.3).
- **AI is bounded.** AI is called only for the semantic tasks already
  approved in `docs/06-ai-agents.md`, with structured, validated, bounded
  output.
- **Java owns orchestration and state.** Python does not schedule,
  orchestrate, persist, or own ingestion state
  (`docs/03-technical-spec.md` Section 7.4; `docs/04-architecture.md`
  Section 3.3).
- **External content is untrusted.** Collected source content is treated as
  untrusted input throughout the pipeline (`docs/03-technical-spec.md`
  Section 20.2).
- **No silent loss.** Failures are visible and retryable where appropriate;
  nothing is silently dropped (`docs/02-functional-spec.md` R9-equivalent
  "no silent drop"; `docs/03-technical-spec.md` Section 13.1).

---

## 3. Source Boundary

A **Source** is a configured, curated information origin
(`docs/02-functional-spec.md` Section 4; `docs/05-data-model.md` Section 7).
Ingestion consumes source configuration as its input; it does not define what
a source is beyond what those two documents already establish.

The ingestion architecture must support, functionally:

- **add** a source;
- **edit** a source's configuration;
- **enable** a source;
- **disable** a source;
- **replace** a source;
- **remove** a source.

(`docs/02-functional-spec.md` Section 4.2, R22.)

**Explicitly preserved, not decided here:**

- the concrete **source types** the MVP supports, and the final curated source
  list (`docs/02-functional-spec.md` Q1);
- whether a **reachability check** is performed at configuration time, and how
  "the same source" is defined for duplicate-source detection
  (`docs/02-functional-spec.md` Q2);
- what happens to already-collected information when a source is **removed or
  replaced** (`docs/02-functional-spec.md` Q3; Section 20 below);
- source link/reachability health checking after collection
  (`docs/02-functional-spec.md` Q17).

No final source-type list and no specific connector framework are defined in
this document.

---

## 4. Source Connectors

A **source connector** is a replaceable adapter responsible for collecting
data from one source type, behind the `SourceCollector` port already
established (`docs/03-technical-spec.md` Section 10.2).

Conceptually, a connector:

- receives the configuration of one Source;
- accesses the configured source;
- collects the information currently available from it;
- returns raw collected item(s) together with source/provenance context (the
  originating Source, and, where available, a source-provided item reference
  and the original URL);
- reports a collection failure when it occurs, rather than failing silently.

A connector must **not**:

- decide relevance — that is a downstream AI capability
  (`docs/06-ai-agents.md` Section 4.3);
- decide importance — likewise downstream (`docs/06-ai-agents.md`
  Section 4.4);
- generate summaries (`docs/06-ai-agents.md` Section 4.5);
- write business state directly — persistence is a Java/application
  responsibility, not the connector's (`docs/03-technical-spec.md`
  Section 6.4);
- access an AI provider directly — a connector's job is fetching, not
  semantic processing;
- implement global ingestion orchestration — sequencing across sources and
  stages is owned by Java, not by any one connector
  (`docs/03-technical-spec.md` Section 6.3).

**Java owns orchestration around connectors** — scheduling which sources to
collect from and when, and driving what happens to a connector's output next
(Section 16). Connectors remain plug-and-play: adding a new source type means
implementing this port once, with no change to normalization, deduplication,
AI processing, signals, search, or alerting (`docs/03-technical-spec.md`
Section 10.2, 21.2).

No exact connector interface/class and no final list of supported source
types are defined here.

---

## 5. Collection

```text
Configured Source
      ↓
Source Connector
      ↓
Collected raw item(s)
```

The collection stage should:

- retrieve information from the configured source;
- preserve original source metadata;
- preserve the original URL/reference, where available;
- preserve the collection timestamp;
- hand its output to normalization in a form normalization can consistently
  process (`docs/03-technical-spec.md` Section 10.1).

This produces a **Raw Information Item** as defined in
`docs/05-data-model.md` Section 8: the item as collected, before normalization
has run.

No exact metadata field set is invented here — the minimum visible metadata
set remains an open question (`docs/02-functional-spec.md` Q11). No
crawler/scraper framework is defined or assumed.

**The MVP remains based on reliable, curated sources — not unrestricted
Internet crawling.** This is a product boundary, not a technical limitation
this document introduces (`docs/01-product-spec.md` Section 8;
`docs/02-functional-spec.md` Section 1).

---

## 6. Parsing and Content Extraction

Three conceptually distinct steps sit between "a source responded" and "we
have collected content":

1. **Fetching** a source's response.
2. **Extracting** the useful content from that response.
3. **Normalizing** that content (Section 7).

Source responses may take different source-specific shapes — HTML, feeds,
structured data, or other formats — depending on the source type
(`docs/02-functional-spec.md` Q1 leaves the concrete types open). The
ingestion architecture isolates this source-specific parsing/extraction inside
the connector boundary (Section 4), so it never leaks into the
business-processing pipeline that follows.

No exact parsing library, no generic browser-automation system, and no
headless-browser infrastructure are chosen or assumed here — none of these are
approved by any prior document. This section stays conceptual; detailed,
source-specific parsing is future implementation work, not part of this
document.

---

## 7. Normalization

**Normalization is deterministic preparation of a collected item for
downstream processing** (`docs/02-functional-spec.md` Section 7.3;
`docs/03-technical-spec.md` Section 7.3).

Conceptual responsibilities, as already established:

- normalize the item's textual content into a consistent internal form;
- normalize/record the original URL/reference;
- normalize/record timestamps (collection time; original publication time
  where available);
- normalize/preserve source metadata (which Source, its type);
- determine and record the item's **language**
  (`docs/02-functional-spec.md` Section 15.3);
- remove irrelevant transport/format artifacts (markup, encoding noise, and
  similar) without changing what the content says;
- produce a stable representation that exact deduplication (Section 8) and AI
  processing (Section 10) can consistently operate on.

**Normalization must not modify the semantic meaning of source content** — it
prepares content for processing; it does not summarize, reinterpret, or
selectively drop meaning-bearing text. **An LLM is not used for this
deterministic step** — nothing in the approved documents requires or implies
AI involvement in normalization (`docs/03-technical-spec.md` Section 3.3).

No exact normalization rule set is invented here; the objective is simply
**consistent input for later stages**.

---

## 8. Exact Deduplication

Exact deduplication is **deterministic**, using the identity/content
mechanisms already established in `docs/05-data-model.md` Sections 17–18:

- source identity;
- external (source-provided) item identity, where available;
- URL/provenance reference;
- content hash of the normalized content.

```text
Collected item
      ↓
Exact duplicate?
   ┌──┴──┐
  yes    no
   ↓      ↓
 skip /  continue
 reuse
```

A "yes" attaches the new item's source reference to the existing record rather
than creating a second one (`docs/02-functional-spec.md` Section 7.4, R5;
`docs/05-data-model.md` Section 18). Running collection repeatedly against a
source that returns the same item again must not create duplicate business
records (`docs/03-technical-spec.md` Section 10.4).

**No final uniqueness strategy (which combination of identity/hash fields
decides "exact duplicate") is fixed here** — the physical identifier strategy
remains open (`docs/05-data-model.md` Section 17). **An LLM is not used for
exact duplicate detection** — this step is entirely deterministic
(`docs/03-technical-spec.md` Section 3.3; `docs/06-ai-agents.md` Section 4.1,
item 3).

---

## 9. Semantic Near-Duplicate Processing

This section aligns strictly with `docs/05-data-model.md` Section 18,
`docs/06-ai-agents.md` Section 4.1, and `docs/07-rag.md` Section 13.

- **Near-duplicate detection is semantic**, not exact-match: it identifies an
  item that reports the same underlying story as an existing one, with
  different wording (`docs/02-functional-spec.md` Section 7.4).
- **It may use embeddings/similarity.** The Near-Duplicate Assessment
  capability may compute a similarity score using an embedding model or
  deterministic vector math over already-computed embeddings
  (`docs/06-ai-agents.md` Section 4.1, item 6).
- **Java owns the final deterministic threshold decision.** Whether a
  similarity score counts as "near-duplicate" is a deterministic comparison in
  Java, not something the AI capability decides on its own
  (`docs/03-technical-spec.md` Section 10.5).

**Exact deduplication (Section 8) happens first, and only non-identical items
proceed to semantic near-duplicate processing** — this avoids unnecessary AI
work for items that are already exact matches
(`docs/03-technical-spec.md` Section 10.5, "deterministic first... semantic
second").

**Not decided here, and not decided by any prior document:**

- the near-duplicate **threshold** (`docs/02-functional-spec.md` Q12;
  `docs/03-technical-spec.md` Section 24, T15);
- the **embedding model** and vector dimension used for similarity
  (`docs/03-technical-spec.md` Section 24, T3);
- the **pgvector index strategy** used to support similarity comparisons
  (`docs/03-technical-spec.md` Section 24, T7);
- a final grouping algorithm beyond "attach the corroborating item to the
  existing Relevant Information record" (`docs/05-data-model.md` Section 18) —
  **no complex clustering** is introduced.

---

## 10. Relevant Information and AI Processing

Once an item is normalized and has passed deduplication, it enters the AI
processing stages already defined in `docs/06-ai-agents.md`:

```text
Normalized item
      ↓
Classification
      ↓
Relevance assessment
      ↓
Relevant Information
      ↓
Importance assessment
      ↓
Signal decision
```

Using the exact distinctions established in `docs/06-ai-agents.md`:

- **Classification** (`docs/06-ai-agents.md` Section 4.2) assigns the item to
  one or more of the **six** already-approved areas of interest, and may
  identify the type of information. It does **not** create a new taxonomy and
  does not introduce a signal-kind model.
- **Relevance assessment** (`docs/06-ai-agents.md` Section 4.3) asks: **is
  this information relevant to the user's configured interests?** A "yes"
  retains the item as **Relevant Information**
  (`docs/05-data-model.md` Section 9).
- **Importance assessment** (`docs/06-ai-agents.md` Section 4.4) asks: **is
  this relevant information important enough to become a Signal?**

**No scores, formulas, ranking systems, signal taxonomy, opportunity
detection, or prediction are invented here** — none of these exist in the
approved product or AI-capability model
(`docs/01-product-spec.md` Section 9; `docs/02-functional-spec.md` R21, R23;
`docs/06-ai-agents.md` Section 4.2–4.4).

---

## 11. Signal Creation and Summary

```text
Relevant Information
      ↓
Importance assessment          (AI — docs/06 §4.4)
      ↓
Java deterministic decision
      ↓
Signal
      ↓
AI Summary                     (docs/06 §4.5)
```

**The AI does not create the Signal record or mutate its lifecycle.** The
importance assessment is advisory input to a Java decision
(`docs/06-ai-agents.md` Section 4.4, item 6; `docs/04-architecture.md`
Section 8.2).

**Java:**

- validates the AI result against its contract schema
  (`docs/03-technical-spec.md` Section 8.3);
- applies the approved business decision (which, in its exact criteria, is not
  defined by this document — see below);
- persists the Signal;
- maintains provenance (Section 13);
- triggers later deterministic behavior (e.g. evaluating whether to alert,
  `docs/02-functional-spec.md` Section 11).

**Python:**

- provides the importance assessment;
- generates the Summary once a Signal exists (`docs/06-ai-agents.md`
  Section 4.5).

**The exact signal-selection criteria are not defined here.** This document
describes only the shape of the transition (an AI assessment feeding a
deterministic Java decision), not the criteria behind it. Preserved:

- **Q4** — signal-selection criteria (`docs/02-functional-spec.md` Q4);
- **T16** — how the signal decision consumes the importance assessment, i.e.
  any Java-side guardrails around it (`docs/03-technical-spec.md` Section 24,
  T16).

Summary behavior remains exactly as defined in `docs/06-ai-agents.md`
Section 4.5 — concise, source-grounded, structured output. Preserved:

- **Q14** — expected summary form/length (`docs/02-functional-spec.md` Q14).

---

## 12. Embeddings and Knowledge Base

Once content becomes part of the knowledge base (i.e. it is retained as
Relevant Information and, where applicable, promoted to a Signal), embedding
generation may be required for:

- **semantic search** (`docs/07-rag.md` Section 9);
- **Q&A retrieval** (`docs/07-rag.md` Section 10);
- **near-duplicate processing** (Section 9 above; `docs/07-rag.md`
  Section 13).

Using the architecture from `docs/07-rag.md`:

```text
Content / retrieval passage
      ↓
Python Embedding Generation     (docs/06 §4.6)
      ↓
Embedding
      ↓
Java persistence
      ↓
PostgreSQL + pgvector
```

This document does not decide, and does not re-decide beyond
`docs/07-rag.md`:

- exact chunking or passage boundaries (`docs/07-rag.md` Section 4, 18);
- the exact placement of embeddings — which record(s) an embedding represents
  (`docs/07-rag.md` Section 3, 6, 18);
- the embedding model or vector dimension (**T3**);
- the pgvector index strategy (**T7**).

RAG is not redesigned here; see `docs/07-rag.md` for the full retrieval and
question-answering architecture.

---

## 13. Provenance

Provenance is mandatory throughout ingestion, exactly as established in
`docs/05-data-model.md` Section 15. At every stage, the relationship below
must be preserved:

```
Source
  ↓
Raw Information Item
  ↓
Relevant Information
  ↓
Signal
  ↓
Summary
  ↓
Retrieval representation
```

Provenance must survive:

- **collection** — the Source and, where available, the original URL and
  source-provided identity are recorded at the moment of collection
  (Section 5);
- **normalization** — normalization changes representation, not provenance
  (Section 7);
- **deduplication** — exact and near-duplicate grouping preserve every
  contributing item's source reference rather than discarding it
  (Sections 8–9; `docs/02-functional-spec.md` Section 7.4);
- **AI processing** — classification, relevance, and importance assessments
  are about the item; they do not detach it from its source
  (Section 10–11);
- **signal creation** — a Signal retains the source(s) of its underlying
  Relevant Information (Section 11; `docs/05-data-model.md` Section 10);
- **summary generation** — a Summary is shown together with references to the
  source content it summarizes (`docs/06-ai-agents.md` Section 4.5, item 8);
- **RAG indexing** — an embedding does not replace or substitute for
  provenance; every retrieved passage remains traceable back to its original
  source (`docs/07-rag.md` Section 11).

No physical database column is defined here, and no new provenance model is
introduced — `docs/05-data-model.md` is the authority for the provenance
model itself; this document only confirms that ingestion, at every stage,
respects it.

---

## 14. Processing State

Every Raw Information Item has an explicit, persisted processing state
(`docs/03-technical-spec.md` Section 3.5, 10.3; `docs/05-data-model.md`
Section 14), so ingestion can distinguish, conceptually, between states such
as: **pending**, **processing**, **completed**, **failed**, and **retryable
failure**.

**These are not asserted as final exact enum values.**
`docs/03-technical-spec.md` Section 10.3 marks its illustrative state sequence
as not yet finalized, and `docs/05-data-model.md` Section 14 explicitly
carries that forward; this document does the same rather than inventing a
closed vocabulary.

The state must support:

- **idempotency** — a stage's outcome is written once per item per stage, by
  upsert, so re-running converges rather than duplicates
  (`docs/03-technical-spec.md` Section 10.4);
- **failure visibility** — a failed stage is recorded with which stage failed,
  why, and whether it is retryable (`docs/03-technical-spec.md`
  Section 13.1–13.2);
- **restart/resume** — because state is persisted before and after each
  stage, processing can continue from where it left off
  (`docs/03-technical-spec.md` Section 13.6);
- **retry** — retryable failures are retried within a bounded policy
  (Section 17);
- **operational understanding** — the state is enough for the activity view to
  explain what happened to an item (`docs/02-functional-spec.md` Section 15.1;
  `docs/05-data-model.md` Section 13).

**Java owns processing state.** Python capabilities do not own global
pipeline state, or any state at all between requests
(`docs/03-technical-spec.md` Section 7.1, 7.4). This is not an
event-sourcing architecture — processing state is a bounded, per-item lifecycle
field, not a reconstructable event log (`docs/05-data-model.md` Section 13).

---

## 15. Idempotency and Restartability

Ingestion must behave predictably in each of the following situations, without
creating duplicate business records or corrupting provenance:

- **The same source is collected repeatedly.** Re-collecting an item the
  connector has already returned converges to the same stored record via
  deterministic identity (Section 8; `docs/03-technical-spec.md`
  Section 10.4).
- **A processing stage is retried.** Each stage's output is written by upsert
  keyed to the item and stage, so a retry after a partial failure produces one
  correct result, not a duplicate (`docs/03-technical-spec.md` Section 10.4).
- **The application restarts.** Because state is persisted at every stage
  boundary (Section 14), work resumes from the last completed stage rather
  than restarting the whole item from scratch or being lost.
- **A Python AI call fails.** The affected item is marked pending/failed at
  that stage; other items are unaffected (`docs/03-technical-spec.md`
  Section 13.6).
- **Collection partially succeeds.** Items already retrieved before a failure
  are kept and processed; the failure is still recorded
  (`docs/02-functional-spec.md` Section 6.2).
- **Processing resumes after failure.** A retryable failure re-enters
  processing at the failed stage, not from the beginning
  (`docs/03-technical-spec.md` Section 10.3).

**Core principle: retrying must not create duplicate business records or
corrupt provenance.**

No exact implementation strategy (specific upsert mechanics, locking
approach, or storage pattern) is invented here. Consistent with the existing
architecture, this relies on: **Java-owned state**, **PostgreSQL** as the
single store, **deterministic identifiers/constraints** where appropriate
(Sections 8, 17), and **explicit processing state** (Section 14) — **no**
distributed locks, queues, Kafka, Redis, or workflow engines are introduced
(`docs/03-technical-spec.md` Section 25).

---

## 16. Collection Cadence and Scheduling

**Scheduling is a Java responsibility.** The backend's scheduler triggers
collection and drives pending work; no Python capability schedules anything
(`docs/03-technical-spec.md` Section 6.3, 6.5).

The ingestion architecture supports **automated collection** — collection runs
without the user manually intervening after sources are configured
(`docs/02-functional-spec.md` Section 6.1).

**Preserved, not decided here:**

- **Q9** — the collection cadence, and whether it is user-configurable
  (`docs/02-functional-spec.md` Q9);
- **Q8** — whether a manual "collect now" trigger exists
  (`docs/02-functional-spec.md` Q8);
- **Q10** — the retry/backoff policy for a failing source
  (`docs/02-functional-spec.md` Q10);
- **T17** — manual "collect now" and manual reprocessing controls, and whether
  they are exposed as endpoints (`docs/03-technical-spec.md` Section 24, T17).

No cron frequency, scheduler library, or exact interval is chosen here, and no
external workflow scheduler is introduced — scheduling remains an in-process
Java responsibility (`docs/03-technical-spec.md` Section 6.5, 25).

---

## 17. Retry and Failure Handling

Using the taxonomy already established in `docs/03-technical-spec.md`
Section 13, applied to ingestion specifically:

**Source failures** — e.g. source unavailable, timeout, malformed response, a
temporary network error. Contained at the collector boundary (Section 4); that
source's collection fails, other sources are unaffected, and the failure is
retried on the next scheduled collection (`docs/02-functional-spec.md`
Section 6.2; `docs/03-technical-spec.md` Section 13.1).

**Processing failures** — e.g. parsing failure, normalization failure,
database failure. Marked failed at the specific stage, with the item retained
and its provenance preserved; other items are unaffected
(`docs/02-functional-spec.md` Section 7.7; `docs/03-technical-spec.md`
Section 13.1, 13.6).

**AI failures** — e.g. provider unavailable, timeout, invalid structured
output, invalid embedding. Handled per the bounded repair and typed-error
behavior already defined (`docs/03-technical-spec.md` Section 9.6;
`docs/06-ai-agents.md` Sections 7, 10).

**Existing bounded retry behavior is respected, not redefined.** Retries are
bounded, with exponential backoff and jitter, applied only to retryable
categories (`docs/03-technical-spec.md` Section 13.2–13.3). The concrete
retry counts, backoff parameters, and timeout values in
`docs/03-technical-spec.md` Section 13.3–13.4 are **proposed defaults, open
to tuning** — this document does not treat them as final, and does not invent
new ones. Preserved:

- **T9** — concrete retry counts, backoff, and timeout values
  (`docs/03-technical-spec.md` Section 24, T9);
- **Q11** — the minimum visible metadata set, where it affects what a failure
  record can show (`docs/02-functional-spec.md` Q11);
- **T17** — manual reprocessing controls (as above).

---

## 18. Failure Visibility

Failures must be understandable. The ingestion architecture provides enough
information to know:

- what **source** failed;
- what **stage** failed;
- whether **retry** is possible;
- whether the item is **pending / failed / completed**;
- whether processing **can resume**.

This is the same "basic visibility" MVP requirement already defined —
Activity Records give the user this information without a dashboard
(`docs/02-functional-spec.md` Section 15.1; `docs/03-technical-spec.md`
Section 15.1; `docs/05-data-model.md` Section 13). **No observability
dashboard and no dedicated monitoring system are created here** — that remains
an explicitly deferred, later concern
(`docs/01-product-spec.md` Section 8; `docs/03-technical-spec.md`
Section 14.5, 25).

Preserved: **Q22 / T18** — activity-history retention
(`docs/02-functional-spec.md` Q22; `docs/03-technical-spec.md` Section 24,
T18).

---

## 19. Manual Operations

The following manual operational actions are conceptually recognized, without
being implemented or designed in detail here:

- **collect now** — trigger collection for a source outside its normal
  schedule;
- **retry failed processing** — re-attempt a failed stage for an item;
- **reprocess an item** — re-run processing from a given stage.

No exact UI or API operation is defined. Preserved:

- **Q8** — manual "collect now" (`docs/02-functional-spec.md` Q8);
- **Q25 / T17** — manual retry/reprocessing controls
  (`docs/02-functional-spec.md` Q25; `docs/03-technical-spec.md` Section 24,
  T17).

No workflow engine is designed to support these operations — if/when
approved, they are expected to be thin, Java-owned operations over the
existing processing-state model (Section 14), not a new orchestration layer.

---

## 20. Source Disable / Remove / Replace Behavior

- **Disabled source.** No new collection occurs while a source is disabled.
  Information already collected from it is **not** automatically deleted
  (`docs/02-functional-spec.md` R7). This is settled behavior, not open.
- **Removed source.** What happens to already-collected information (kept,
  marked orphaned, or deleted) follows the **unresolved** removal semantics —
  it is not decided by this document (`docs/02-functional-spec.md` Q3;
  `docs/05-data-model.md` Section 21).
- **Replaced source.** The system must preserve provenance for information
  already collected under the prior configuration, and must not silently treat
  a replacement as if it were the same source unless that is explicitly
  defined — which it currently is not (`docs/02-functional-spec.md` Q2 [same
  source definition], Q3).

**No final historical deletion or cascade rule is invented here.** Preserved:

- **Q3** — effect of source removal/replacement on already-collected
  information (`docs/02-functional-spec.md` Q3);
- **Q2** — source reachability and "same source" definition, where relevant to
  distinguishing a replacement from an edit (`docs/02-functional-spec.md` Q2).

---

## 21. Security and Untrusted Content

External source content is untrusted, exactly as already established
(`docs/03-technical-spec.md` Section 20.2; `docs/07-rag.md` Section 15).
Ingestion, specifically:

- must **not execute** source content;
- must **not treat source text as system instructions** — text inside a
  collected item is data to be normalized, deduplicated, classified, assessed,
  and summarized, never a command to any component processing it;
- must **not allow source-controlled content to modify business logic** — a
  source cannot, through its content, change how it or any other source is
  processed;
- must **not bypass validation** at any stage — normalized content, AI
  requests, and AI responses are all subject to the same validation already
  defined (`docs/03-technical-spec.md` Section 8.3, 20.1).

This document respects the **SSRF and content-safety requirements** already
defined for source fetching in `docs/03-technical-spec.md` Section 20.2 —
scheme restrictions, size limits, redirect limits, blocking of
private/loopback/link-local addresses, and pinning the validated DNS answer for
the actual connection so a rebinding hostname cannot bypass the check
(`docs/adr/0017-outbound-fetch-ssrf-dns-rebinding.md`) — belong at the connector
boundary (Section 4) — without redesigning them here. **No new security
subsystem is designed in this document.** Detailed security architecture belongs
to `docs/10-security.md`.

---

## 22. Ingestion and RAG Boundary

The boundary between ingestion and RAG is explicit:

**Ingestion:**

- collects (Section 5);
- normalizes (Section 7);
- deduplicates, exact and semantic (Sections 8–9);
- processes (classification, relevance, importance — Sections 10–11);
- persists business information (Sections 11, 13).

**RAG** (`docs/07-rag.md`):

- prepares/uses retrieval representations (passages);
- generates embeddings;
- performs semantic retrieval;
- supplies context for grounded question answering.

The two are connected — ingestion is what makes content available for RAG to
index and retrieve (Section 12) — but they are **not conflated**: ingestion
owns collecting and deciding what belongs in the knowledge base; RAG owns
making that knowledge base semantically searchable and answerable-from. See
`docs/07-rag.md` for the full retrieval and Q&A design.

---

## 23. End-to-End Ingestion Flow

```text
Configured reliable source
          ↓
      Collection
          ↓
 Parsing / extraction
          ↓
      Normalization
          ↓
   Exact deduplication
          ↓
 Semantic near-duplicate
          ↓
     Classification
          ↓
       Relevance
          ↓
 Relevant Information
          ↓
      Importance
          ↓
 Java signal decision
      ┌───┴────┐
   no signal  signal
      │          ↓
      │       Summary
      │          ↓
      └──────► Knowledge Base
                     ↓
               Embeddings
                     ↓
                pgvector
                     ↓
          Search / Grounded Q&A
```

This diagram is **conceptual**. It does not imply that every implementation
detail, or the exact ordering of every sub-step (for example, precisely when
embedding generation runs relative to summary generation), has been finalized
where the existing specifications leave that open
(`docs/03-technical-spec.md` Section 10.1, "functional description, not an
implementation constraint"; `docs/07-rag.md` Section 13).

---

## 24. Deterministic vs. AI Responsibilities

| Step | Responsibility |
|---|---|
| Source scheduling | Java |
| Source connector orchestration | Java |
| Collection | Deterministic / source-specific (Java, via a connector) |
| Parsing / extraction | Deterministic |
| Normalization | Deterministic |
| Exact deduplication | Deterministic |
| Near-duplicate semantic assessment | AI/embedding similarity + deterministic decision |
| Classification | AI |
| Relevance | AI |
| Importance assessment | AI |
| Signal state transition | Java / deterministic |
| Summary | AI |
| Persistence | Java |
| Embedding generation | Python |
| Vector persistence | Java |
| Retrieval | Java |
| Q&A synthesis | Python |
| Alerts | Java |

The distinction is consistent throughout this document and every prior one:
**AI assesses and produces semantic output; Java owns business decisions and
state** (`docs/03-technical-spec.md` Section 3.3; `docs/04-architecture.md`
Section 8).

---

## 25. MVP Boundaries

**Ingestion in the MVP DOES include:**

- reliable, configured sources;
- source management (add/edit/enable/disable/replace/remove);
- automated collection;
- normalization;
- exact deduplication;
- semantic near-duplicate handling;
- classification;
- relevance assessment;
- importance assessment;
- Signal creation;
- summary generation;
- provenance, end to end;
- persistence;
- explicit processing state;
- retries and failure handling;
- basic activity/failure visibility;
- the embedding/indexing work needed for RAG (Section 12).

**Ingestion in the MVP does NOT include:**

- unrestricted web crawling;
- arbitrary Internet discovery;
- sophisticated source-authority scoring;
- complex ranking;
- forecasting;
- prediction;
- opportunity detection;
- a recommendation engine;
- a distributed workflow engine;
- Kafka or any message broker;
- Redis;
- Kubernetes;
- multiple ingestion microservices;
- autonomous agents;
- a full observability platform.

This matches, and does not expand, the boundaries already fixed in
`docs/01-product-spec.md` Section 9, `docs/02-functional-spec.md` Section 2.2,
and `docs/03-technical-spec.md` Section 25.

---

## 26. Open Questions

This document resolves none of the following; each is carried forward from
the specification that originated it, using existing identifiers only.

- **Q1** — Concrete source types and the final curated source list
  (`docs/02-functional-spec.md` Q1; Section 3).
- **Q2** — Source reachability check and "same source" definition
  (`docs/02-functional-spec.md` Q2; Section 3, 20).
- **Q3** — Effect of source removal/replacement on already-collected
  information (`docs/02-functional-spec.md` Q3; Section 20).
- **Q5** — Whether a source can be associated with specific interests/areas
  (`docs/02-functional-spec.md` Q5).
- **Q6** — Whether a whole area can be disabled, and the exact form of an
  interest, where relevant to what ingestion filters against
  (`docs/02-functional-spec.md` Q6).
- **Q8** — Manual "collect now" trigger (`docs/02-functional-spec.md` Q8;
  Section 16, 19).
- **Q9** — Collection cadence (`docs/02-functional-spec.md` Q9; Section 16).
- **Q10** — Retry/backoff policy for a failing source
  (`docs/02-functional-spec.md` Q10; Section 17).
- **Q11** — Minimum visible metadata set, where relevant to failure/activity
  visibility (`docs/02-functional-spec.md` Q11; Section 17).
- **Q12 / T15** — Near-duplicate criteria/threshold
  (`docs/02-functional-spec.md` Q12; `docs/03-technical-spec.md` Section 24,
  T15; Section 9).
- **Q13** — Whether the user can browse not-relevant (noise) items and correct
  misclassifications (`docs/02-functional-spec.md` Q13).
- **Q15** — Signal lifecycle details, where relevant to what happens after
  Signal creation (`docs/02-functional-spec.md` Q15; Section 11).
- **Q17** — Source-link health checking (`docs/02-functional-spec.md` Q17;
  Section 3).
- **Q22 / T18** — Activity-history retention (`docs/02-functional-spec.md`
  Q22; `docs/03-technical-spec.md` Section 24, T18; Section 18).
- **Q23** — Whether in-progress work resumes or restarts after Signal Engine
  is down (`docs/02-functional-spec.md` Q23; Section 15).
- **Q25 / T17** — Manual retry/reprocessing controls
  (`docs/02-functional-spec.md` Q25; `docs/03-technical-spec.md` Section 24,
  T17; Section 16, 19).
- **T3** — Embedding model / vector dimension (`docs/03-technical-spec.md`
  Section 24, T3; Section 9, 12).
- **T7** — pgvector index strategy (`docs/03-technical-spec.md` Section 24,
  T7; Section 9, 12).
- **T9** — Retry counts, backoff, and timeout values
  (`docs/03-technical-spec.md` Section 24, T9; Section 17).
- **T12** — Future Python service split (`docs/03-technical-spec.md`
  Section 24, T12; `docs/06-ai-agents.md` Section 13).
- **T16** — Signal-decision guardrails (`docs/03-technical-spec.md`
  Section 24, T16; Section 11).
- **T19** — Multilingual behavior (`docs/03-technical-spec.md` Section 24,
  T19; `docs/02-functional-spec.md` Q24).

No new question identifier is created; every item above uses an identifier
already established in `docs/02-functional-spec.md` or
`docs/03-technical-spec.md`.

---

## 27. Decisions vs. Open Questions

### Already decided (restated from prior documents, not new here)

- Java owns ingestion orchestration (`docs/03-technical-spec.md` Section 6.3,
  6.5; `docs/04-architecture.md` Section 7).
- Python owns the semantic AI capabilities used during ingestion
  (`docs/06-ai-agents.md` Sections 4.1–4.5).
- PostgreSQL is the source of truth for everything ingestion persists
  (`docs/03-technical-spec.md` Section 11.1).
- Sources are configurable and replaceable (`docs/02-functional-spec.md`
  R22).
- Provenance is mandatory (`docs/01-product-spec.md` Section 3).
- Exact deduplication is deterministic (`docs/03-technical-spec.md`
  Section 10.5).
- Near-duplicate processing is semantic, with a deterministic final decision
  (`docs/03-technical-spec.md` Section 10.5).
- The Signal state transition is Java-owned; AI never writes it directly
  (`docs/03-technical-spec.md` Section 3.3; `docs/06-ai-agents.md`
  Section 4.4).
- AI output is validated and bounded (`docs/03-technical-spec.md`
  Section 8.3, 9.6).
- There is no unrestricted Internet monitoring in the MVP
  (`docs/01-product-spec.md` Section 8).
- No unnecessary distributed infrastructure is introduced
  (`docs/03-technical-spec.md` Section 25).

### Conceptually defined here (this document's contribution)

- The **connector boundary** — what a connector does and must not do
  (Section 4).
- The **collection → parsing/extraction → normalization → exact deduplication
  → semantic near-duplicate → classification → relevance → importance →
  signal → summary** flow at the ingestion level (Sections 5–11, 23).
- The **ingestion/AI boundary** — which steps are deterministic and which are
  AI, restated as one table (Section 24).
- The **ingestion/RAG boundary** — what belongs to each (Section 22).
- The **role of explicit processing state** within ingestion specifically
  (Section 14).
- The **idempotency and restart principles** as they apply to the ingestion
  pipeline (Section 15).

### Still open

Every item listed in Section 26, and only those items — no additional open
question is introduced beyond what is already known to be undecided.

---

## 28. Summary

Signal Engine ingestion is a **simple, Java-orchestrated, source-driven
pipeline**. It collects from reliable, configured sources through replaceable
connectors; deterministically normalizes and deduplicates what it collects;
delegates the semantic judgements — near-duplicate similarity,
classification, relevance, importance, and summarization — to the focused
Python AI capabilities defined in `docs/06-ai-agents.md`; and persists only
validated business state, exclusively in PostgreSQL, exclusively through the
Java backend. Provenance is preserved at every stage, from the original
source through to a Signal and its Summary, and on into the retrieval
representations described in `docs/07-rag.md`. Processing state is explicit
and idempotent, so failures are visible, retries are safe, and work can resume
without duplicating records or losing provenance — all without introducing any
infrastructure beyond what `docs/03-technical-spec.md` already approves.
