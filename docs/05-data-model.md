# Signal Engine — Data Model

Document ID: `05-data-model.md`
Status: Draft — awaiting review
Depends on: `CLAUDE.md`, `docs/01-product-spec.md`, `docs/02-functional-spec.md`,
`docs/03-technical-spec.md`, `docs/04-architecture.md`

---

## 1. Purpose and Scope

This document defines the **conceptual and logical persistence model** of
Signal Engine: what information the system needs to remember, how those pieces
of information relate to one another, who owns them, and what state and
provenance they must carry.

It exists because `docs/03-technical-spec.md` and `docs/04-architecture.md`
already establish **that** PostgreSQL is the system of record and **how** the
system is structured, but neither defines **what is persisted**. This document
fills that gap at the conceptual/logical level, one step before implementation.

The following are already decided and are only restated here where needed for
context — this document does not re-derive them:

- **PostgreSQL is the system of record** for all business data
  (`docs/03-technical-spec.md` Section 4.2, 11.1; `docs/04-architecture.md`
  Section 9).
- **pgvector is part of PostgreSQL**, not a separate store; vector data is
  conceptually part of the same persistence model, not a second database
  (`docs/03-technical-spec.md` Section 4.3, 11.2; `docs/04-architecture.md`
  Section 9).
- **Java owns business persistence.** The Java backend is the only component
  with a connection to business tables; Python holds no business data
  (`docs/03-technical-spec.md` Section 6.4, 7.4; `docs/04-architecture.md`
  Section 9).

This document does **not**:

- define SQL, table DDL, column types, indexes, or Flyway migrations — that is
  future implementation work, out of scope for every document up to this point
  (`docs/03-technical-spec.md` Section 1.3);
- define Java persistence classes, repositories, or entities;
- introduce any concept, relationship, or field not already implied by
  `docs/01-product-spec.md`, `docs/02-functional-spec.md`,
  `docs/03-technical-spec.md`, or `docs/04-architecture.md`;
- resolve any open product, functional, or technical question. Where the model
  depends on one, it is named and left open (Section 25).

Version numbers and technology justifications are not repeated here; see
`docs/03-technical-spec.md` Section 4 and 19 for those.

---

## 2. Data Modeling Principles

These principles are already established by the prior specifications and are
binding for this model:

1. **PostgreSQL is the single source of truth.** No second database, no
   separate vector store (`docs/03-technical-spec.md` Section 25;
   `docs/04-architecture.md` Section 9).
2. **Business state is owned by Java.** The persistence model exists for the
   Java backend to read and write; Python never owns a row of business data
   (`docs/03-technical-spec.md` Section 7.4; `docs/04-architecture.md`
   Section 9).
3. **Provenance is mandatory.** Every piece of derived or user-visible
   information must remain traceable to the source it came from
   (`docs/01-product-spec.md` Section 3; `docs/02-functional-spec.md`
   Section 14; Section 15 below).
4. **Processing state is explicit.** Where information moves through a
   pipeline, its position is a persisted fact, not something inferred
   (`docs/03-technical-spec.md` Section 3.5, 10.3; Section 14 below).
5. **Processing must be idempotent.** Re-processing an item must not create
   duplicates or double-count (`docs/03-technical-spec.md` Section 10.4).
6. **AI output is persisted only after validation.** A capability's structured
   output is schema-validated before anything derived from it is written
   (`docs/03-technical-spec.md` Section 3.4, 8.3, 9.6).
7. **AI must not directly mutate business state.** An AI capability returns a
   result; a Java use case decides what, if anything, is persisted as a
   consequence (`docs/03-technical-spec.md` Section 6.4; `docs/04-architecture.md`
   Section 8.3).
8. **External source content is untrusted input.** Fetched content is stored
   and processed as data, never executed or trusted at face value
   (`docs/03-technical-spec.md` Section 20.2).
9. **Configuration must remain changeable.** Sources and interests are
   configuration data, not business logic, and must be editable without
   affecting unrelated persisted information
  (`docs/02-functional-spec.md` R22).
10. **The model supports source replacement without destroying unrelated
    data.** Disabling, editing, or replacing a source must not cascade into
    unrelated collected information, signals, or knowledge
    (`docs/02-functional-spec.md` R7; Section 21 below).
11. **The model supports future multilingual evolution without forcing it into
    MVP behavior.** Language is a first-class, recordable fact on collected
    content, even though only English is processed into signals in the MVP
    (`docs/02-functional-spec.md` Section 15.3; `docs/03-technical-spec.md`
    Section 24, T19).
12. **Avoid unnecessary tables and abstractions.** A concept is modeled only if
    an approved specification requires it; nothing is added for symmetry or
    anticipated convenience (`docs/03-technical-spec.md` Section 3.8).

No additional modeling principle is introduced beyond this list.

---

## 3. Core Domain Concepts

The following persisted concepts are already established by
`docs/01-product-spec.md`, `docs/02-functional-spec.md`, and
`docs/03-technical-spec.md`. Each is summarized here; several are elaborated in
their own section below (Sections 5–14).

| Concept | Purpose | Why it exists | Main relationships | Lifecycle | Does **not** represent |
|---|---|---|---|---|---|
| **Area of Interest** | Organize the user's interests into the six predefined areas | The product's fixed areas of interest (`docs/01-product-spec.md` Section 1.1) | Groups zero or more Interests; matched by Relevant Information | Fixed set for the MVP, no lifecycle of its own | A processing engine or a separate pipeline (Section 5) |
| **Interest** | Let the user refine what counts as relevant within an area | Optional, user-defined refinement of relevance (`docs/02-functional-spec.md` Section 5) | Belongs to one Area of Interest; matched by Relevant Information | User-configurable: added, edited, enabled/disabled, removed | A scoring rule or a personalization profile (Section 6) |
| **Source** | Represent one configured, curated information origin | The unit of ingestion configuration (`docs/02-functional-spec.md` Section 4) | Produces Raw Information Items; may relate to Areas/Interests (open, Q5) | Added, edited, enabled/disabled, replaced, removed | An authority tier or a trust score (Section 7) |
| **Raw Information Item** | Represent one item as collected, before assessment | The unit of collection (`docs/02-functional-spec.md` Section 6) | Belongs to one Source; carries a Processing State; may become Relevant Information | Collected → normalized → deduplicated → assessed | A signal (Section 8) |
| **Relevant Information** | Represent an item found relevant to the user's interests | The retained result of relevance assessment (`docs/02-functional-spec.md` Section 7.5, 7.7) | Derived from one or more corroborating Raw Information Items; may relate to Areas/Interests; may become a Signal | Created once assessed relevant; retained in the knowledge base | A guaranteed signal — not everything relevant is a signal (Section 9) |
| **Signal** | Represent information important enough to bring to the user's attention | The product's core output concept (`docs/01-product-spec.md` Section 1) | Derived from one Relevant Information; has a Summary; may receive Feedback; may produce an Alert | New → Reviewed → Kept/Dismissed (`docs/02-functional-spec.md` Section 9.3) | A category, kind, opportunity, prediction, or recommendation (Section 10) |
| **Summary** | Give a concise, source-grounded account of a Signal | Required so the user can triage without opening the original (`docs/02-functional-spec.md` Section 8) | Belongs to one Signal; grounded in the same sources | Generated once a Signal exists; may be pending/failed/regenerated | The signal itself, or a translation (Section 11) |
| **Feedback** | Capture the user's relevant/not-relevant judgement on a Signal | Explicit MVP capability (`docs/02-functional-spec.md` Section 10) | Belongs to one Signal | Recorded with a timestamp; change/withdrawal is open (Q18) | A training example or a reward signal (Section 12) |
| **Activity Record** | Give basic visibility into what the system did | Required minimal observability (`docs/03-technical-spec.md` Section 15.1) | Associated with a Source, a Raw Information Item, or a system operation | Append-oriented; retention is open (Q22) | A full observability/event-sourcing store (Section 13) |
| **Embedding** | Vector representation of searchable content | Enables semantic search and retrieval (`docs/03-technical-spec.md` Section 11.2) | Associated with content that must be semantically searchable (Relevant Information and/or Signal content) | Generated by Python, stored and queried by Java | A second database or a standalone concept unrelated to its source content (Section 16) |
| **Processing State** | Track a Raw Information Item's position in the pipeline | Required explicit lifecycle (`docs/03-technical-spec.md` Section 3.5, 10.3) | Attached to a Raw Information Item | Stage-by-stage, persisted before and after each stage | A generic status flag with no defined stages (Section 14) |

No signal taxonomy, source-authority taxonomy, opportunity score, prediction
entity, recommendation entity, trend entity, or ranking entity is defined —
none of these exist in the approved product model
(`docs/01-product-spec.md` Section 9; `docs/02-functional-spec.md` Section 2.2,
R21, R22).

---

## 4. Conceptual Relationship Model

```
Area of Interest
      │
      └── Interest
              │
              │  (interests/areas are matched against, not modified by,
              │   the concepts below)
              ▼
Source ──────────► Raw Information Item ──── Processing State
                          │                   (attached; tracks the
                          │                    item's pipeline position)
                          │
                          ▼
                   Relevant Information ◄──── matched Area(s) / Interest(s)
                          │
                          ├──────────────► Signal
                          │                   │
                          │                   ├── Summary
                          │                   │
                          │                   └── Feedback
                          │
                          └── (associated) Embedding
                                            │
                          Signal / Summary content
                          may also be associated with
                          an Embedding (exact placement
                          deferred — Section 16)

Activity Record: not part of the chain above. It observes and records
outcomes of Source collection, Raw Information Item processing, and
configuration operations (adding/editing a Source or Interest). It is
associated *with* these operations, not nested under Signal or Relevant
Information.
```

Notes on deliberate deviations from a purely symmetrical diagram:

- **Feedback is attached to the Signal**, not to Relevant Information, because
  the functional specification defines feedback as a judgement on a signal
  (`docs/02-functional-spec.md` Section 10, R8).
- **A Raw Information Item may corroborate an existing Relevant Information**
  record rather than always creating a new one: near-duplicate items are
  grouped, and the grouped Relevant Information retains references to every
  contributing Raw Information Item (`docs/02-functional-spec.md` Section 7.4,
  R5). This is why the arrow from Raw Information Item to Relevant Information
  is many-to-one, not strictly one-to-one.
- **Embedding** is drawn as associated content rather than nested under one
  fixed concept, because `docs/03-technical-spec.md` Section 11.2 explicitly
  defers the exact placement of embeddings (which record(s) they attach to) to
  this document or to `07-rag.md`; this document does not invent that placement
  (Section 16).
- No relationship is drawn from Area of Interest / Interest directly to Raw
  Information Item, because classification and relevance assessment — the step
  that matches an item against areas/interests — produces **Relevant
  Information**, not a change to the raw item itself
  (`docs/02-functional-spec.md` Section 7.5).

---

## 5. Area of Interest

The six areas are fixed product concepts, not a data-modeling decision:

1. AI & Technology
2. Markets & Investment
3. Architecture, Construction & Real Estate
4. Law & Regulation
5. Fashion & Clothing
6. Business & Opportunity Trends

(`docs/01-product-spec.md` Section 1.1; `docs/02-functional-spec.md`
Section 5.1, R20.)

**Areas are organizational, not architectural.** They are not separate
processing engines, not separate services, and do not imply separate
pipelines or per-area logic (`docs/02-functional-spec.md` Section 5.1;
`docs/04-architecture.md` Section 5). One collection, processing, and signal
pipeline serves all six areas uniformly; an item may relate to more than one
area at once (`docs/02-functional-spec.md` R20).

What needs to be persisted, conceptually:

- The **identity** of each of the six areas, so Interests and Relevant
  Information can reference them.
- Nothing beyond that is currently supported: the specifications do not define
  per-area metadata, configuration, or behavior (they are fixed and already
  understood by the system — `docs/02-functional-spec.md` Section 5.1). No
  additional area metadata is invented here.

---

## 6. Interest

An **Interest** is a more specific, optional monitoring interest the user adds
within an Area of Interest (`docs/02-functional-spec.md` Section 5).

- **Relationship to an area:** each Interest belongs to exactly one Area of
  Interest.
- **Configurable nature:** the user can add, edit, enable/disable, and remove
  an Interest (`docs/02-functional-spec.md` Section 5.2). This implies the
  model needs to represent at least: the owning area, the interest's content,
  and an enabled/disabled state.
- **Enabled/disabled behavior:** explicitly supported — an interest can be
  disabled without being deleted, mirroring the same pattern already approved
  for sources (`docs/02-functional-spec.md` Section 5.2).
- **Participation in relevance assessment:** Interests (together with the
  areas themselves) are compared against incoming information during relevance
  assessment; a match is recorded on the resulting Relevant Information
  (`docs/02-functional-spec.md` Section 7.5). Monitoring works even with zero
  interests configured — the six areas alone are enough
  (`docs/02-functional-spec.md` Section 5.3).
- **No scoring system, no personalization model.** An interest is a plain
  refinement of relevance, not a weighted profile, and there is no
  recommendation model behind it (`docs/02-functional-spec.md` out-of-scope,
  Section 2.2).

**Explicitly open** (not resolved here):

- The exact **form** an interest takes (free text, keywords, a short
  description) and whether a whole area can be disabled
  (`docs/02-functional-spec.md` Q6).
- Whether a **Source** can be associated with specific interests/areas, or
  every enabled source is evaluated against all interests
  (`docs/02-functional-spec.md` Q5).
- Whether changing an interest causes **previously processed information** to
  be re-evaluated (`docs/02-functional-spec.md` Q7).

---

## 7. Source

A **Source** is the configured, curated information origin Signal Engine
collects from (`docs/02-functional-spec.md` Section 4;
`docs/03-technical-spec.md` Section 4.1–4.2 [architecture, not versions]).

What needs to be persisted, conceptually:

- **Source identity** — enough to distinguish one configured source from
  another, and to keep referencing it as its configuration is edited.
- **Source type** — the category/capability needed to collect from it; the
  concrete set of source types is not yet fixed
  (`docs/02-functional-spec.md` Q1), so this is modeled as an identifying
  attribute whose vocabulary is still open, not a fixed enumeration here.
- **Source reference/configuration** — whatever functional configuration lets
  the system reach and identify the source (a name/label and a reference),
  per `docs/02-functional-spec.md` Section 4.2. The exact configuration shape
  per source type is left to `08-ingestion.md`.
- **Enabled/disabled state** — a source can be disabled without being deleted;
  disabling stops future collection but retains already-collected information
  (`docs/02-functional-spec.md` R7).
- **Provenance** — every item collected from a source retains a reference back
  to that source (Section 15).
- **State visible to the user** — the current enabled/disabled state and the
  outcome of the last collection attempt are part of what the user can see
  about a source (`docs/02-functional-spec.md` Section 4.2), which implies the
  model needs to support recording a "last collection outcome" concept. No
  numeric health/reachability score is defined — reachability checking itself
  is an open question (`docs/02-functional-spec.md` Q2, Q17).

**Replaceability is a modeling requirement, not just a behavior.** A source
can be added, edited, enabled, disabled, replaced, or removed, and doing so
must not require changing unrelated collected information, processing,
relevance, signal, summary, alerting, search, or question-answering data
(`docs/02-functional-spec.md` R22). Concretely: a source is configuration data
that Raw Information Items reference, not data that other concepts embed or
duplicate.

**No source-authority taxonomy exists in this model.** There is no
`PRIMARY/OFFICIAL`, `SPECIALIZED`, or `TREND/WEAK-SIGNAL` classification, no
authority score, and no trust score — these were explicitly removed from the
product model (`docs/01-product-spec.md` Section 12, "Source strategy —
DEFINED... Simplified on 2026-09-04"; `docs/02-functional-spec.md` R22).

**Explicitly open:**

- The concrete source **types** and the final curated source list
  (`docs/02-functional-spec.md` Q1).
- Whether a **reachability check** is performed at configuration time, and how
  "the same source" is defined for duplicate-source detection
  (`docs/02-functional-spec.md` Q2).
- What happens to already-collected information when a source is **removed or
  replaced** (`docs/02-functional-spec.md` Q3; Section 21 below).

---

## 8. Raw Information Item

A **Raw Information Item** is one item as collected from a source, before
processing (`docs/02-functional-spec.md` Section 1, glossary).

What needs to be persisted, conceptually:

- **Source association** — which Source produced it.
- **Source-provided identity**, where the source offers one — part of the
  deterministic identity used for idempotent collection
  (`docs/03-technical-spec.md` Section 10.4).
- **Content identity/hash** — a deterministic fingerprint of the normalized
  content, used for exact-duplicate detection (Section 18).
- **Original URL/reference**, where available — the link back to the source,
  mandatory for provenance (Section 15).
- **Collection metadata** — at minimum, the time it was collected.
- **Raw vs. normalized content** — the item as fetched is distinct from its
  normalized form; normalization is a defined, deterministic pipeline stage
  that produces a consistent internal representation from the raw payload
  (`docs/02-functional-spec.md` Section 7.3; `docs/03-technical-spec.md`
  Section 10.1). Conceptually, both the fact of collection and the normalized
  content need to be represented, even though the exact physical shape (e.g.
  whether normalized content overwrites or accompanies the raw payload) is an
  implementation decision, not fixed here.
- **Publication time**, where available from the source, distinct from
  collection time (Section 22).
- **Language**, as determined during normalization — always recorded, even for
  content the MVP does not process into a signal
  (`docs/02-functional-spec.md` Section 15.3).
- **Processing state** — its current position in the pipeline (Section 14).
- **Provenance** — the item's source and reference are retained regardless of
  what happens to it downstream (duplicate, not relevant, or promoted).

**Why this is distinct from a Signal.** A Raw Information Item is simply
**collected** information — it exists the moment collection succeeds,
regardless of whether it later turns out to be a duplicate, noise, or
eventually a signal. A **Signal** is information that has been assessed as
relevant and important enough to bring to the user's attention
(`docs/01-product-spec.md` Section 1). The two concepts are never collapsed:
most Raw Information Items never become a Signal, and every Signal traces back
to at least one Raw Information Item, never the reverse.

---

## 9. Relevant Information

**Relevant Information** is the grouped, retained representation of a piece of
information supported by one or more Raw Information Items
(`docs/02-functional-spec.md` Section 7.4, 7.5, 7.7).

**Lifecycle (two steps, mirroring `docs/11-roadmap.md` Phase 5).** A Relevant
Information record is **created as a grouping container at near-duplicate
assessment** — a semantically distinct normalised item anchors a new record, and a
near-duplicate item is attached to the existing one it corroborates
(`docs/adr/0007-semantic-deduplication-relevant-information.md`). It is then
**enriched at relevance assessment**, which adds the relevant/not-relevant verdict,
the matched Area(s) of Interest and Interest(s), and the short user-visible reason
(`docs/03-technical-spec.md` Section 7.2, `assess-relevance`). At creation time the
`reason` and the matched sets are empty; the record is never presented to the user
before relevance assessment has run. Retaining and enriching the record is a Java
decision acting on AI output (`docs/03-technical-spec.md` Section 3.3;
`docs/04-architecture.md` Section 8).

- **Relationship to raw information:** derived from one or more Raw
  Information Items. When a near-duplicate item corroborates an existing one,
  it is grouped into the same Relevant Information rather than creating a
  second record, while every contributing item's source reference is retained
  (`docs/02-functional-spec.md` Section 7.4, R5).
- **Relevance assessment:** produced by a Python capability
  (`docs/03-technical-spec.md` Section 7.2, `assess-relevance`); the assessment
  itself is semantic (AI), but **acting on it — enriching the record with the
  verdict, matched areas/interests, and reason, or setting it aside as noise — is
  a Java decision** (`docs/03-technical-spec.md` Section 3.3;
  `docs/04-architecture.md` Section 8). The record itself already exists from the
  near-duplicate grouping step (see the lifecycle note above).
- **Relationship to interests/areas:** records which Area(s) of Interest and
  which matched Interest(s), if any, the item relates to, plus a short reason
  the user can see (`docs/02-functional-spec.md` Section 7.5, 9.2).
- **AI-generated assessment vs. persisted business state:** the assessment
  (relevant/not relevant, matched areas/interests, reason) is AI output that
  must be schema-validated before it is used to create or update this record
  (`docs/03-technical-spec.md` Section 3.4, 8.3); the retained record itself is
  business state owned by Java.
- **Provenance:** inherits and preserves the provenance of its contributing Raw
  Information Item(s) (Section 15).

**No complex relevance scoring model is defined.** The functional
specification distinguishes relevant from not-relevant and records a reason;
it does not define a numeric score or confidence value
(`docs/02-functional-spec.md` Section 7.5). Whether a relevance
score/confidence value is captured, and in what form, is **not specified by
the approved documents** and is therefore left open rather than invented here.

---

## 10. Signal

A **Signal** is a piece of information that is relevant to the user's
interests and/or important enough to bring to their attention
(`docs/01-product-spec.md` Section 1; `docs/02-functional-spec.md` R21). This
is the only definition of a signal in the model — there is no signal
taxonomy, no signal kind, and no signal category.

- **Relationship to Relevant Information:** a Signal is created from exactly
  one Relevant Information record, when the importance assessment (Section
  9-adjacent capability `assess-importance`,
  `docs/03-technical-spec.md` Section 7.2) determines it is important enough.
  Not every piece of Relevant Information becomes a Signal
  (`docs/02-functional-spec.md` Section 7.6).
- **Source provenance:** a Signal retains the source(s) of its underlying
  Relevant Information, including original references/links, so it always
  remains traceable (`docs/02-functional-spec.md` Section 9.2; Section 15
  below).
- **Importance assessment:** the *assessment* ("is this important enough?") is
  semantic and produced by Python; the *state transition* that actually
  creates the Signal, sets its state, and decides whether to alert is
  deterministic and owned by Java
  (`docs/03-technical-spec.md` Section 3.3; `docs/04-architecture.md`
  Section 7.1, 8.2). AI never writes a Signal record directly.
- **Lifecycle/state:** at minimum, the states already defined —
  **New → Reviewed → Kept / Dismissed**
  (`docs/02-functional-spec.md` Section 9.3). Whether "Reviewed" is tracked
  automatically and whether dismissed signals are hidden or retained remain
  open (`docs/02-functional-spec.md` Q15). Signals are retained as knowledge
  regardless of state unless a future decision says otherwise
  (`docs/02-functional-spec.md` Section 9.3).
- **Summary relationship:** a Signal has one Summary (Section 11); the summary
  may be pending if not yet generated (`docs/02-functional-spec.md`
  Section 9.5, W6 error cases).
- **Alerting relationship:** creating a Signal that is important enough may
  raise an Alert (Section 19); the alert references the Signal, not the
  reverse (`docs/02-functional-spec.md` Section 11).
- **Feedback relationship:** a Signal may receive Feedback from the user
  (Section 12), which updates the Signal's state
  (`docs/02-functional-spec.md` R8).

**What is deliberately not represented:** no signal kind/category field, no
opportunity/prediction/recommendation attributes, no authority-weighted
scoring, and no representation of financial, legal, or investment advice —
none of these exist in the approved product or functional model
(`docs/01-product-spec.md` Section 9; `docs/02-functional-spec.md` Section 2.2,
R21, R25).

**Explicitly open:** the exact **signal-selection criteria** — how "important
enough" is judged — is a product question, not a data-modeling one
(`docs/02-functional-spec.md` Q4). This document does not imply a threshold,
score, or field that would encode such a criterion; the model only needs a
place to record the outcome of that (still-undefined) decision.

---

## 11. Summary

A **Summary** is the concise, source-grounded account of a Signal
(`docs/02-functional-spec.md` Section 8).

- **Relationship to Signal:** one Summary per Signal, generated once the
  Signal is created; conceptually attached to relevant information's content
  since it is grounded in the same underlying material
  (`docs/02-functional-spec.md` Section 8.2–8.3).
- **Source-grounded nature:** the summary must not introduce facts the source
  does not support, and generated interpretation must be distinguishable from
  facts drawn from the source (`docs/02-functional-spec.md` Section 8.2, R4).
- **AI-generated nature:** produced by a Python capability (`summarize`,
  `docs/03-technical-spec.md` Section 7.2) as structured output.
- **Validation:** like all AI output that feeds business logic, the structured
  summary is schema-validated before it is persisted
  (`docs/03-technical-spec.md` Section 3.4, 9.4, 9.6).
- **Provenance relationship:** shown together with references to the source
  content it summarizes (`docs/02-functional-spec.md` Section 8.2; Section 15
  below).
- **Lifecycle/replacement:** a summary can be **pending** (not yet generated)
  or **failed** (generation did not succeed), and can be retried
  (`docs/02-functional-spec.md` Section 8.3, W5 error cases;
  `docs/03-technical-spec.md` Section 9.6). Whether a summary can be
  regenerated/replaced after the fact is not addressed by the approved
  specifications and is not invented here.

**No exact summary length or format is defined.** The functional specification
only requires a concise, triage-sufficient summary
(`docs/02-functional-spec.md` Section 8.2); the precise expected length/form is
an **open question** (`docs/02-functional-spec.md` Q14). Task 7's implementation
(`docs/adr/0008-relevance-importance-signal-summary.md`) carries only a
**provisional** guide — "at most 4 sentences" in the prompt, plus a generous
character cap that guards against runaway generation — and stores the summary as
free text with no length constraint, so Q14 can still be settled later without a
schema change.

---

## 12. Feedback

**Feedback** captures the user's relevant/not-relevant judgement on a Signal
(`docs/02-functional-spec.md` Section 10).

- **Relationship to Signal:** Feedback is attributed to exactly one Signal
  (`docs/02-functional-spec.md` R8) — not to Relevant Information directly
  (Section 4's diagram note).
- **Relevant / not relevant:** the minimum required value; feedback updates the
  Signal's lifecycle state accordingly (`docs/02-functional-spec.md`
  Section 10.2–10.3).
- **Timestamp/provenance:** feedback is recorded with a timestamp
  (`docs/02-functional-spec.md` Section 10.3, W7).
- **Effect on business state:** feedback is retained and attributed to the
  signal; it **may** inform future relevance assessment, but it **must not**
  be used for automated model training or fine-tuning in the MVP
  (`docs/02-functional-spec.md` R8, Section 9). No model-training table,
  reward model, preference-learning pipeline, or complex feedback-scoring
  structure is part of this model.

**Explicitly open:** whether feedback can be **changed or withdrawn**, whether
**free-text comments** are supported alongside the relevant/not-relevant
value, and how (if at all) feedback affects future relevance assessment
(`docs/02-functional-spec.md` Q18). This document does not decide any of
these; it only notes that Feedback conceptually needs to support being
recorded against a Signal with a timestamp, at minimum.

---

## 13. Activity Record

An **Activity Record** provides basic visibility into what the system did —
collection outcomes, processing outcomes, and failures — as required by
`docs/02-functional-spec.md` Section 15.1 and `docs/03-technical-spec.md`
Section 15.1.

Conceptually supported examples, all already established:

- **Collection activity** — per source, the outcome of a collection attempt
  (success with item count, or failure with a reason)
  (`docs/02-functional-spec.md` Section 6.2, W3).
- **Processing activity** — counts/outcomes of items normalized,
  de-duplicated, set aside as noise, retained as relevant information, or
  promoted to signals, and processing failures
  (`docs/03-technical-spec.md` Section 15.1).
- **Failures** — what failed, when, and why, with enough context to identify
  the source or item involved (`docs/02-functional-spec.md` Section 15.2;
  `docs/03-technical-spec.md` Section 13).
- **Alerting activity** — which alerts were raised and whether delivery
  succeeded (`docs/02-functional-spec.md` Section 11.2).

**Activity Records are operational/product visibility data, not a substitute
for structured logs or distributed traces.** Structured logs and
OpenTelemetry traces remain the primary mechanism for deep diagnostic detail
(`docs/03-technical-spec.md` Section 14); Activity Records are the
coarser-grained, user-facing subset of that visibility, persisted so the user
can browse it through the product (`docs/02-functional-spec.md` W11).

**This is not an event-sourcing or audit-log architecture.** Activity Records
do not reconstruct system state, do not form a distributed event store, and
are not a telemetry database — they are a bounded, product-level activity
feed, consistent with the MVP's explicitly limited observability scope
(`docs/01-product-spec.md` Section 8; `docs/03-technical-spec.md`
Section 15.1, 25).

**Explicitly open:** how long activity history is retained, and whether old
activity is purged (`docs/02-functional-spec.md` Q22;
`docs/03-technical-spec.md` Section 24, T18). The model only needs to support
recording activity; it does not fix a retention period.

---

## 14. Processing State

Every Raw Information Item has an explicit, persisted processing state, so its
position in the pipeline is always knowable rather than inferred
(`docs/03-technical-spec.md` Section 3.5, 10.3; `docs/04-architecture.md`
Section 12).

The model needs to support the approved pipeline stages, illustrated in
`docs/03-technical-spec.md` Section 10.3:

```
received → normalized → (duplicate | deduplicated)
        → classified → relevance-assessed → (not-relevant | relevant)
        → importance-assessed → (signal-created | no-signal)
        → summarized → complete

any stage → failed { stage, reason, retryable }
failed (retryable) → pending-retry → (re-enters at the failed stage)
```

`docs/03-technical-spec.md` Section 10.3 marks this state set as
**illustrative**, with the final vocabulary assigned to this document — this
document, in turn, does not fix exact enum values or names either, because the
functional selection criteria that some of these transitions depend on (in
particular, the signal decision, `docs/02-functional-spec.md` Q4) are not yet
settled. **The exact state vocabulary therefore remains to be finalized**,
consistent with `docs/03-technical-spec.md` Section 10.3; what is fixed is the
architectural requirement itself:

- **State transitions are controlled by Java** — no stage's outcome is written
  directly by an AI capability (`docs/04-architecture.md` Section 8.2).
- **Processing is stage-oriented** — normalization, deduplication, relevance
  assessment, importance assessment, and summarization are distinct,
  separately recorded steps (`docs/03-technical-spec.md` Section 7.2, 10.1).
- **Stages are idempotent** — re-running a stage converges to one correct
  result rather than duplicating state (`docs/03-technical-spec.md`
  Section 10.4; Section 17 below).
- **Failures are explicit** — a failed stage is recorded with which stage
  failed, why, and whether it is retryable
  (`docs/03-technical-spec.md` Section 13.1–13.2).
- **Processing can resume after restart** — because state is persisted before
  and after each stage, work can continue from where it left off
  (`docs/03-technical-spec.md` Section 13.6; `docs/04-architecture.md`
  Section 12).
- **One failed item must not block unrelated items** — processing state is
  tracked per item, not globally (`docs/02-functional-spec.md` R10 equivalent;
  `docs/03-technical-spec.md` Section 13.6).

---

## 15. Provenance Model

Provenance is a defining product property
(`docs/01-product-spec.md` Section 3) and is therefore central to this data
model. The conceptual chain that must remain traceable end to end:

```
Source
   │  (identity + reference)
   ▼
Raw Information Item
   │  (source reference retained; original URL where available;
   │   collection time)
   ▼
Relevant Information
   │  (retains every contributing Raw Information Item's source
   │   reference, including corroborating near-duplicates)
   ▼
Signal
   │  (retains the same source references, shown to the user)
   ▼
Summary
   (grounded in, and referencing, the same source content)
```

At every step, what must be preserved:

- **Original source URL/reference**, where available — carried from the Raw
  Information Item all the way through to the Signal and Summary
  (`docs/02-functional-spec.md` Section 14.2, R1).
- **Source identity** — which configured Source an item came from, even after
  the source is later edited, disabled, or (subject to Q3) removed.
- **Collected item identity** — so a Signal or Relevant Information record can
  always be traced back to the specific Raw Information Item(s) it derives
  from, including when multiple sources corroborate the same information
  (`docs/02-functional-spec.md` Section 7.4, R1).
- **Relationship between derived information and source content** — a Summary
  and a Relevant/Signal record are never presented without their supporting
  source content being reachable (`docs/02-functional-spec.md` R1; Section 16
  covers the equivalent rule for question-answering citations).

This is **traceability, not academic citation formatting** — the model needs
enough structure for the user to reach the original source and verify a claim,
not a formal citation graph, footnote system, or bibliography model. No
citation framework beyond what `docs/02-functional-spec.md` Section 14 and
Section 13 (question answering) already require is introduced.

---

## 16. Embeddings and Vector Data

- **Conceptual ownership:** embeddings belong to persisted content that must
  be semantically searchable or retrievable — conceptually, Relevant
  Information and Signal content (and by extension their Summaries), per
  `docs/02-functional-spec.md` Section 12 (search) and Section 13 (question
  answering).
- **Storage:** embeddings are stored in PostgreSQL via **pgvector**, not a
  separate vector database (`docs/03-technical-spec.md` Section 4.3, 11.2;
  `docs/04-architecture.md` Section 9). *Task 8.3B (`docs/adr/0012`) made this
  concrete: `rag_passage` and `rag_passage_embedding` (migration V11), a
  `vector(768)` column, cosine distance, no ANN index yet (T7). See `07-rag.md`
  Section 23.*
- **Ownership of persistence and retrieval:** the Java backend owns embedding
  persistence and performs retrieval (the nearest-neighbor query plus metadata
  filtering); it is not a Python responsibility
  (`docs/03-technical-spec.md` Section 9.8, 11.3; `docs/04-architecture.md`
  Section 9).
- **Generation:** embeddings are generated by a Python capability (`embed`,
  `docs/03-technical-spec.md` Section 7.2).
- **Answer synthesis:** for question answering, Java performs retrieval and
  supplies the retrieved passages to Python, which synthesizes an answer
  strictly from what it was given (`docs/03-technical-spec.md` Section 9.8;
  `docs/04-architecture.md` Section 6, Section 9).

**What is deliberately left open, per `docs/03-technical-spec.md` Section 24:**

- The **embedding model** and **vector dimension** (T3) — not chosen here.
- The **vector index type and parameters** (HNSW vs. IVFFlat, and their
  tuning) (T7) — not chosen here.
- The **exact placement** of embeddings — which record(s) (Relevant
  Information, Signal, Summary, or a derived chunk of one of these) an
  embedding attaches to, and at what granularity — is explicitly deferred by
  `docs/03-technical-spec.md` Section 11.2 to this document or to
  `docs/07-rag.md`; this document records the conceptual association above
  without fixing that placement, leaving the precise design to `07-rag.md`.
  *Task 8.3B resolves the **granularity**: an embedding attaches to a derived
  **RAG passage** (`rag_passage`), a technical chunk of persisted content, not
  directly to a business record. **Which** persisted content is chunked and
  indexed is resolved by the index-population wiring: a Raw Information Item
  once relevance assessment confirms it (i.e. it becomes retained Relevant
  Information), indexed automatically at that point — not the Signal or
  Summary text, and not items relevance assessment set aside as not relevant.*

**Changing the embedding model or dimension requires a controlled
re-embedding strategy** — existing vectors would no longer be comparable to
newly generated ones — but the migration process itself is not designed in
this document (`docs/03-technical-spec.md` Section 11.2 references
`docs/07-rag.md` for that).

---

## 17. Identifiers and Uniqueness

Conceptual identity rules already established
(`docs/03-technical-spec.md` Section 10.4):

- **Source identity** — each configured Source is distinctly identifiable, so
  it can be referenced by every item it produces, independent of later edits
  to its configuration.
- **Source-provided item identity**, where the source offers one — part of a
  Raw Information Item's deterministic identity.
- **Content hash** — a fingerprint of the normalized content, used together
  with (or in place of) the source-provided identity when a source has none,
  and used for exact-duplicate detection (Section 18).
- **Original URL** — a provenance attribute (Section 15), and in practice
  often part of what makes an item identifiable, but conceptually distinct
  from identity/hash: a URL can change or go stale while identity and
  provenance must not be lost.
- **Deterministic item identity** — the combination of "source id + external
  item id where available, plus content hash" is what collection uses to
  decide whether an item is new or already known, enabling idempotent
  collection (`docs/03-technical-spec.md` Section 10.4).
- **Idempotency** — because identity is deterministic, re-collecting or
  re-processing the same item converges to the same stored result rather than
  duplicating it (`docs/03-technical-spec.md` Section 10.4).

**Distinguishing the four related-but-different notions:**

| Notion | What it is | Purpose |
|---|---|---|
| Identity | A stable reference to "this specific item/source/signal" | Lets other records point at it reliably |
| Content hash | A fingerprint of normalized content | Detects exact duplicates deterministically |
| URL | A reachable reference to the original | Lets the user verify the source; may go stale |
| Provenance | The recorded chain back to the source (Section 15) | Preserves traceability regardless of identity/URL changes |

**No physical identifier strategy is fixed here.** Whether identifiers are
UUIDs, sequential/bigint keys, or natural keys is an implementation decision
not addressed by any approved specification, and is therefore explicitly left
open rather than chosen in this document.

*Resolved as implementation work by `docs/adr/0001-persistence-schema-foundation.md`
(as `docs/11-roadmap.md` Section 5 and Section 19 authorise): transactional
tables use `UUID PRIMARY KEY DEFAULT gen_random_uuid()`, and `area_of_interest`
uses its stable `TEXT` code as a natural primary key. This section deliberately
left the choice open; ADR 0001 records it and its rationale.*

---

## 18. Deduplication and Near-Deduplication Data

The model must support the approved two-step split
(`docs/03-technical-spec.md` Section 10.5; `docs/04-architecture.md`
Section 7.1):

1. **Exact duplicate detection — deterministic.** An item whose identity or
   content hash exactly matches an already-known item is a duplicate; it does
   not create a new Raw Information Item record, and its source reference is
   attached to the existing one (`docs/02-functional-spec.md` Section 7.4).
2. **Semantic near-duplicate detection — may use embeddings.** An item that
   reports the same underlying story with different wording is a
   near-duplicate; detecting this may use an embedding similarity score (a
   Python capability), but the **final decision** — whether the similarity
   score crosses the threshold that makes it a near-duplicate — is
   deterministic Java logic acting on that score
   (`docs/03-technical-spec.md` Section 10.5).

For both cases, the model needs to support recording that multiple Raw
Information Items (and their source references) contributed to one Relevant
Information record, so the user can see that a signal was corroborated by
more than one source (`docs/02-functional-spec.md` Section 7.4, 9.4).

**The near-duplicate threshold is explicitly open** and is not invented here
(`docs/02-functional-spec.md` Q12; `docs/03-technical-spec.md` Section 24,
T15). The model records that a similarity comparison and a configurable
threshold decision exist as a mechanism; it does not fix a numeric value.

**No complex duplicate graph is introduced.** The approved behavior is
grouping (one Relevant Information record with multiple contributing raw
items), not a general many-to-many similarity graph between arbitrary items —
nothing in the specifications supports a richer structure than that.

---

## 19. Alert-Related Data

Only the data-model implications of the already-approved **simple** alerting
capability (`docs/02-functional-spec.md` Section 11) are covered here; the
alert subsystem itself is not designed in this document.

- **Relationship between Signal and Alert:** an alert is raised **only** for a
  new Signal that is important enough to warrant attention; one alert
  corresponds to one qualifying new Signal
  (`docs/02-functional-spec.md` Section 11.2). Conceptually, an Alert record
  (if modeled as its own concept) references the Signal it is about, not the
  reverse.
- **A record of alerts raised** is required, so the user can see which alerts
  were raised (`docs/02-functional-spec.md` Section 11.2), which implies at
  least: which Signal, and when.
- **Delivery state:** whether alert *delivery* (as opposed to alert *raising*)
  needs its own persisted state depends on the still-undecided notification
  channel; the functional specification does note that a delivery failure
  must be recorded and visible in activity, and that the signal remains
  available regardless of delivery outcome
  (`docs/02-functional-spec.md` Section 11.3, Section 15.2). This document
  notes that requirement without designing a channel-specific delivery model.
- **Failure/retry visibility:** delivery failures are recorded via the
  Activity Record concept (Section 13), consistent with the general failure
  visibility rule; the retry policy for failed delivery is open
  (`docs/02-functional-spec.md` Q20).

**Explicitly open and not decided here:** the notification **channel** — no
choice of email, Telegram, Discord, Slack, push notification, SMS, or webhook
is made or implied (`docs/02-functional-spec.md` Q19). **No
per-area, per-interest, or per-source alert configuration is modeled**, because
it is explicitly out of scope (`docs/02-functional-spec.md` Section 11.2,
R6).

---

## 20. Search and Q&A Data

The model needs to support:

- **Semantic search** over Relevant Information and Signals: an embedding of
  the user's query compared against stored embeddings (Section 16), combined
  with **deterministic metadata filters** (area of interest, source, time
  period, state) applied in SQL (`docs/02-functional-spec.md` Section 12;
  `docs/03-technical-spec.md` Section 11.3).
- **Source-grounded question answering:** the Java backend performs
  retrieval (embedding the question, querying pgvector, applying metadata
  filters) and supplies the **retrieved passages** — with their source
  references — to the Python `answer` capability, which must cite only those
  passages or state that the knowledge base has insufficient information
  (`docs/02-functional-spec.md` Section 13; `docs/03-technical-spec.md`
  Section 9.8).

This reaffirms the same division of responsibility established in
`docs/03-technical-spec.md` and `docs/04-architecture.md`: **Java performs
retrieval, pgvector is the retrieval mechanism, Python only synthesizes an
answer from what it is explicitly given**, and the answer must remain
traceable to those supplied passages (Section 15).

**Not introduced:**

- **Chat history persistence** or any general-assistant memory — Signal
  Engine is explicitly not a general-purpose chatbot
  (`docs/02-functional-spec.md` Section 13.1).
- **Autonomous agent memory** or a **conversation graph** — no such concept
  exists in the approved specifications.
- **A separate/external vector database** — retrieval uses pgvector only
  (Section 16).

**Explicitly open:** whether question answering supports **multi-turn
context**, or each question is independent, is not decided
(`docs/02-functional-spec.md` Q21). If multi-turn context were ever approved,
it would require a persisted conversation concept that does **not** currently
exist in this model; this document does not anticipate that structure.

---

## 21. Lifecycle and Deletion/Replacement Semantics

Only what is already supported is described here.

- **Disabling a source** stops future collection but **retains** information
  already collected from it — this is settled behavior, not open
  (`docs/02-functional-spec.md` R7, Section 4.4).
- **Removing or replacing a source:** what happens to information already
  collected from it — kept, marked orphaned, or deleted — is an **open
  functional question** (`docs/02-functional-spec.md` Q3). This document does
  not invent an answer; it only notes that whatever the eventual answer, the
  provenance chain (Section 15) for information already surfaced to the user
  should not be silently broken, since that would conflict with the mandatory
  provenance principle (Section 2, item 3). This is a constraint the eventual
  answer must satisfy, not a resolution of Q3 itself.
- **Existing Signals and Relevant Information** are not retroactively deleted
  when configuration (an interest or a source) changes; changes apply to
  information processed **after** the change
  (`docs/02-functional-spec.md` Section 5.4, R16-equivalent, Q7 for whether
  re-evaluation of prior information is ever triggered).
- **Old collected information** (Raw Information Items, Relevant Information,
  Signals, Summaries, Feedback, and Embeddings) is retained for the life of
  the MVP install; no retention period is defined for this data
  (`docs/03-technical-spec.md` Section 11.4). This is distinct from
  **Activity Record** retention, which is separately open (Section 13, Q22).

**No cascading deletion rule, no retention period, and no soft-delete
mechanism is invented in this document.** Where the approved specifications
are silent (in particular, the consequence of removing a source, Q3), this
document preserves the silence rather than filling it.

---

## 22. Temporal Data

Conceptual timestamps the model may require, only where an approved
specification supports them:

| Timestamp | Meaning | Time domain |
|---|---|---|
| Source publication time | When the original source published the content, where available | External/source time |
| Collection time | When Signal Engine collected the item | System processing time |
| Processing time(s) | When each pipeline stage ran (normalization, relevance assessment, importance assessment, summarization) | System processing time |
| Signal creation time | When the Signal record was created | System processing time |
| Feedback time | When the user gave feedback | System processing time (user action) |
| Activity time | When a recorded activity event occurred | System processing time |
| Update time | When a record was last changed, where change is possible (e.g. a source's configuration) | System processing time |

The key distinction the model must preserve is between **external/source
time** (when something happened or was published according to the source) and
**system processing time** (when Signal Engine acted upon it) — both are
referenced throughout `docs/02-functional-spec.md` (e.g. Section 7.3,
Section 9.2: "original publication time where available, collection time,
signal creation time").

No exact timestamp type, precision, or timezone-handling detail is defined
here — those are implementation decisions, not part of the conceptual model.

---

## 23. Constraints and Integrity Rules

Logical constraints already implied by the approved architecture and
specifications. Each is marked as either a **conceptual integrity rule**
(binding now, at the model level) or a note that it will later become a
**physical database constraint** (implementation work, not defined here).

| # | Rule | Kind |
|---|------|------|
| 1 | A Raw Information Item belongs to exactly one Source. | Conceptual now; a foreign-key-equivalent constraint later |
| 2 | Relevant Information, Signals, and Summaries remain traceable to the Raw Information Item(s)/Source(s) they derive from (Section 15). | Conceptual now; enforced by application logic and, later, by physical references |
| 3 | A Signal cannot exist without the Relevant Information it represents. | Conceptual now; a not-null/foreign-key-equivalent constraint later |
| 4 | Persisted AI output (relevance assessment, importance assessment, summary, embedding, answer) must have passed schema validation before it is written. | Conceptual/application-level now (`docs/03-technical-spec.md` Section 8.3); not something a database constraint alone can guarantee |
| 5 | Business state (Sources, Interests, Raw Information Items, Relevant Information, Signals, Summaries, Feedback, Activity Records) is written only by the Java backend. | Architectural rule, enforced by component boundaries (`docs/04-architecture.md` Section 9), not a database constraint |
| 6 | An Embedding must correspond to identifiable, existing content (Section 16). | Conceptual now; a foreign-key-equivalent constraint later, once embedding placement is finalized (`07-rag.md`) |
| 7 | A Raw Information Item's Processing State must remain consistent with its position in the pipeline (Section 14) — e.g. it cannot be "summarized" without having first been assessed relevant and important. | Conceptual now; enforced by application logic |
| 8 | Provenance (source identity and reference) is never dropped once recorded, regardless of later source edits, disabling, or (subject to Q3) removal. | Conceptual integrity rule, directly derived from `docs/01-product-spec.md` Section 3 |
| 9 | An Interest belongs to exactly one Area of Interest. | Conceptual now; a foreign-key-equivalent constraint later |
| 10 | Feedback is attributed to exactly one Signal. | Conceptual now; a foreign-key-equivalent constraint later |

No database-level constraint (specific SQL `CHECK`, `UNIQUE`, or
`FOREIGN KEY` syntax) is defined in this document; that is implementation
work for the migrations that will eventually implement this model.

---

## 24. Physical PostgreSQL Mapping — High Level Only

This is a conceptual mapping of responsibility, not a schema:

| Concept | Persistence responsibility |
|---|---|
| Area of Interest | Organizational configuration (fixed set) |
| Interest | User monitoring configuration |
| Source | Source configuration |
| Raw Information Item | Collected content and provenance |
| Relevant Information | Relevance result, with provenance to contributing items |
| Signal | User-visible important information, derived from Relevant Information |
| Summary | Source-grounded derived text, attached to a Signal |
| Feedback | User feedback, attached to a Signal |
| Activity Record | System activity visibility |
| Processing State | Pipeline lifecycle, attached to a Raw Information Item |
| Embedding | Vector representation, stored via pgvector, associated with searchable content |

All of these live in the **same PostgreSQL database** that the Java backend
owns (`docs/03-technical-spec.md` Section 11.1). This document does not
define:

- exact table names (none are fixed by any approved specification),
- columns or SQL types,
- indexes,
- foreign-key syntax, or
- migrations.

Those are implementation/migration decisions for later work, executed through
Flyway per `docs/03-technical-spec.md` Section 4.8, once this conceptual model
is reviewed and the still-open questions in Section 25 are far enough along to
support concrete column and constraint decisions.

---

## 25. Open Data-Model Questions

This document resolves none of the following; each is carried forward from
the specification that originated it.

**Product/functional questions this model depends on:**

- **Q4** — Signal-selection criteria (what makes information "important
  enough" to become a Signal). Affects: whether/how an importance outcome is
  recorded on Relevant Information vs. Signal (Section 10).
- **Q9** — Collection cadence. Affects: how often Raw Information Items are
  expected to arrive; does not change the model itself.
- **Q12** — Duplicate vs. near-duplicate criteria and adjustable sensitivity.
  Affects: the near-duplicate threshold used in Section 18.
- **Q18** — Whether feedback can be changed/withdrawn, and whether free-text
  comments are supported. Affects: the shape of the Feedback concept
  (Section 12).
- **Q19** — Notification channel(s) for alerting. Affects: whether a
  channel-specific delivery concept is ever needed (Section 19).
- **Q22** — Activity-history retention. Affects: whether an Activity Record
  purge mechanism is ever needed (Section 13).
- **Q24** — Future multilingual source-language behavior. Affects: whether
  additional language-dependent fields are ever needed beyond the
  already-recorded language attribute (Section 8; Section 2, principle 11).

**Technical questions this model depends on:**

- **T3** — Embedding model and vector dimension (Section 16).
- **T7** — pgvector index type and parameters (Section 16).
- **T15** — Near-duplicate similarity threshold (Section 18) — the mechanism
  (embeddings + a deterministic threshold) is fixed; the value is not.
- **T18** — Activity-history retention/purge job (Section 13).
- **T19** — Multi-language processing path (Section 8, Section 22).

**Additional data-model-relevant open points, named where they arose above:**

- **Q1** — Concrete source types and the final curated source list
  (Section 7).
- **Q2** — Source reachability check and "same source" definition
  (Section 7).
- **Q3** — Effect of removing/replacing a source on already-collected
  information (Section 7, Section 21).
- **Q5** — Whether a source can be associated with specific
  interests/areas (Section 6, Section 7).
- **Q6** — Exact form of an interest; whether a whole area can be disabled
  (Section 6).
- **Q7** — Whether changing an interest/source triggers re-evaluation of
  previously processed information (Section 6, Section 21).
- **Q14** — Expected summary form/length (Section 11).
- **Q15** — Exact signal lifecycle details (automatic "Reviewed" tracking;
  hidden vs. retained dismissed signals) (Section 10).
- **Q17** — Whether original source link health is checked (Section 7).
- **Q20** — Retry policy for failed alert delivery (Section 19).
- **Q21** — Whether question answering supports multi-turn context
  (Section 20).

**Identifier strategy:** the exact physical identifier approach (e.g. UUID vs.
sequential key vs. natural key) is not fixed by any approved specification and
was left open here (Section 17). *It was subsequently decided as implementation
work in `docs/adr/0001-persistence-schema-foundation.md`: UUID primary keys, with
a natural `TEXT` key for `area_of_interest`.*

No new question identifier is created; every open point above uses an
existing identifier from `docs/02-functional-spec.md` Section 17 or
`docs/03-technical-spec.md` Section 24.

---

## 26. Data Model Summary

Signal Engine's conceptual data model has **PostgreSQL as the single source of
truth**, with **pgvector** as an integral part of that same database rather
than a separate store. The **Java backend owns all business state**; Python
never persists business data and only returns validated, structured results
that Java decides whether and how to persist.

**Source provenance is mandatory** and traceable end to end — from a
configured Source, through a collected Raw Information Item, to Relevant
Information, to a Signal, to its Summary — with no step permitted to lose the
reference back to the original source.

**Raw information and signals are kept explicitly distinct**: a Raw
Information Item is simply what was collected; a Signal is what has been
assessed as relevant and important enough to surface. Not everything collected
becomes relevant, and not everything relevant becomes a signal.

**Processing state is explicit, stage-oriented, and idempotent**, so an item's
position in the pipeline is always a persisted fact, one item's failure never
blocks another, and processing can resume safely after an interruption.

**AI-derived information is validated before it is persisted**, and AI never
writes business state directly — the Java backend always makes the
deterministic decision that turns a validated AI assessment into a persisted
fact.

**Embeddings live in pgvector**, generated by Python and retrieved by Java,
with the embedding model, vector dimension, index strategy, and exact
placement all left open for later technical work.

Consistent with the approved product model, this document introduces **no
signal taxonomy, no source-authority taxonomy, and no
recommendation/prediction/opportunity data model**, and it avoids unnecessary
persistence abstractions — every concept here traces to a concrete requirement
in `docs/01-product-spec.md`, `docs/02-functional-spec.md`,
`docs/03-technical-spec.md`, or `docs/04-architecture.md`.

Every open question identified while writing this document — signal-selection
criteria, collection cadence, the near-duplicate threshold, the alert
delivery channel, activity retention, feedback modification/withdrawal
behavior, the embedding model and vector dimension, the vector index strategy,
source removal/replacement semantics, the exact processing-state vocabulary,
the physical identifier strategy, and future multilingual evolution — **remains
explicitly open**, to be resolved by the product, functional, or technical
decision it belongs to, not by this document.
