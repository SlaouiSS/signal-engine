# Signal Engine — Functional Specification

Document ID: `02-functional-spec.md`
Status: Accepted — reviewed as part of the Phase 0 documentation baseline
(`docs/11-roadmap.md` Section 3); decisions this document marks open or
provisional remain open or provisional until resolved.
Depends on: `CLAUDE.md`, `docs/01-product-spec.md`

Scope: Functional behavior only. This document describes **what** Signal Engine
does from the user's perspective. It does **not** define technologies,
frameworks, databases, APIs, protocols, deployment, infrastructure, or software
architecture. Those belong to the later technical specification.

The central objective, from `docs/01-product-spec.md`, is: **help one user stay
up to date on the areas they care about.** This document keeps the functional
scope intentionally simple.

Where a functional point is not decided by `CLAUDE.md` or
`docs/01-product-spec.md`, it is listed in Section 17 (Open Functional Questions)
rather than resolved here.

---

## 1. Terminology

- **area of interest** — one of the six areas in `docs/01-product-spec.md`
  Section 1.1, used only to organize the user's interests.
- **interest** — a more specific monitoring interest the user optionally adds
  within an area.
- **source** — an information origin Signal Engine collects from.
- **raw information item** — one item as collected from a source, before
  processing.
- **relevant information** — an item that processing has found relevant to the
  user's interests, retained in the knowledge base.
- **signal** — a piece of information that is relevant to the user's interests
  and/or important enough to bring to their attention (`docs/01-product-spec.md`
  Section 1). The concept is deliberately kept simple; there is **no signal
  taxonomy** and no signal-kind model.
- **knowledge base** — the retained, searchable store of collected information
  and signals.
- **summary** — a concise, source-grounded summary of a signal.
- **alert** — a simple notification that an important new signal has appeared.
- **feedback** — the user's relevant / not-relevant judgement on a signal.

For the MVP, Signal Engine collects from a **curated set of reliable sources**
configured by the user. It is not open-ended Internet monitoring.

Signal Engine **presents** signals. It does **not** give financial, legal, or
investment advice, does **not** recommend actions, and does **not** promise
returns or outcomes (`docs/01-product-spec.md` Sections 3, 9). No user-facing
output may be framed as advice.

---

## 2. Functional Scope

### 2.1 In scope for the MVP

For a single personal user, Signal Engine must functionally:

1. Let the user configure a **curated set of reliable sources** — add, edit,
   enable, disable, replace, and remove them (Section 4).
2. Let the user optionally define more specific **interests** within the six
   areas of interest (Section 5).
3. **Collect** new information from enabled sources automatically (Section 6).
4. **Normalize** collected information into a consistent internal form
   (Section 7).
5. Detect and remove **duplicates and near-duplicates** (Section 7).
6. Assess whether an item is **relevant** to the user's interests, setting aside
   what is not (noise) (Section 7).
7. Decide whether relevant information is **important enough to become a signal**
   (Section 7).
8. Tag each item with the **area(s) of interest** it relates to (Section 7).
9. Produce a concise, source-grounded **summary** for each signal (Section 8).
10. Let the user **review** signals — what was found, why it is considered
    relevant or important, and where it came from (Section 9).
11. Let the user give **feedback** on a signal (relevant / not relevant)
    (Section 10).
12. **Alert** the user when an important new signal appears (Section 11).
13. Retain collected information and signals as a searchable **knowledge base**
    (Section 12).
14. Provide **semantic search** over the knowledge base, with basic metadata
    filtering (Section 12).
15. Answer **natural-language questions** from the knowledge base, with sources
    (Section 13).
16. Preserve **source and provenance** for every item, signal, summary, and
    answer, including the original link where available (Section 14).
17. Give the user **basic visibility** into what the system did and what failed
    (Section 15).
18. Focus on **English-language source content** for the MVP, while staying open
    to more source languages later (Section 15.3).
19. Present **errors and failures** in an understandable way (Section 15).

### 2.2 Out of scope for the MVP

- Authentication, user accounts, roles, permissions.
- Multiple users, sharing, collaboration, tenancy.
- Any action on external systems beyond notifying the user.
- Financial, legal, or investment advice; recommendations to act; promises of
  returns, opportunities, or outcomes.
- Areas of interest beyond the six in `docs/01-product-spec.md` Section 1.1.
- Open-ended Internet monitoring (the MVP uses a curated source set).
- A signal taxonomy or signal-kind model.
- Any source-authority scoring or weighting model.
- Complex personalization beyond configured interests and relevant / not-relevant
  feedback.
- Forecasting, prediction, trend algorithms, correlation engines, opportunity
  scoring, sophisticated ranking models.
- Per-area, per-interest, or per-source alert rules; complex alert scoring;
  multi-step notification workflows.
- Optional reranking in search or question answering.
- Using feedback for automated model training or fine-tuning.
- Processing source content in languages other than English.
- Presenting machine-translated source content as a feature.
- A dedicated AI-evaluation subsystem or an observability dashboard (anticipated
  later — `docs/01-product-spec.md` Section 8).
- A public API or integration platform for external consumers.
- A general-purpose assistant unrelated to the collected knowledge base.

### 2.3 Actors

| Actor | Description |
|-------|-------------|
| **User** | The single personal user who configures sources and interests, reviews signals, gives feedback, searches, and asks questions. There is one user role and no authentication. |
| **Signal Engine (system)** | The platform performing collection, processing, signal creation, summarization, alerting, search, and answering. |
| **Source** | An information origin the system collects from. Not a human actor. |

---

## 3. Core Functional Flow

```
Reliable sources
      ↓
  Collection
      ↓
 Normalization
      ↓
 Deduplication
      ↓
   Relevance          (is this relevant to the user's interests?)
      ↓
Importance / signal   (is it important enough to surface?)
      ↓
   Summary
      ↓
Signal + source link
      ↓
Search / question answering
      ↓
  Simple alert        (when an important new signal appears)
```

The order is a functional description, not an implementation constraint. No
additional mandatory AI processing stage is introduced beyond those shown.

---

## 4. Source Configuration

### 4.1 Purpose and source strategy

A **source** is an information origin Signal Engine collects from. For the MVP:

- Sources are **reliable and curated** — chosen for trustworthiness across the
  six areas of interest.
- The set of sources is **configuration data**, not business logic.
- Sources are **plug-and-play**: adding, editing, enabling, disabling, replacing,
  or removing a source must not require changing unrelated collection,
  processing, relevance, signal, summary, alerting, search, or question-answering
  behavior.
- Every item collected keeps a reference to its **original source and link**
  where available (Section 14).

> The concrete **source types** (for example web feeds, article pages,
> newsletters, official registers) and the collection mechanism are **not decided
> here** — see Section 17 (Q1). This document describes source configuration
> functionally and does not define connectors.

### 4.2 What the user can do with a source

For each source, the user can functionally:

- **Add** a source by providing its functional configuration (a name / label and
  a reference to the source).
- **Enable or disable** a source without deleting it.
- **Edit** a source's configuration.
- **Replace** a source with another.
- **Remove** a source.
- **View** the configured sources and, for each, its current state (enabled or
  disabled, last successful collection, last error if any).

Whether a source can be associated with specific areas or interests is an open
question (Section 17, Q5).

### 4.3 Initial source set

The MVP uses a **small, curated set of reliable sources** covering the six areas
of interest. The concrete list is **configuration data** and is pending user
validation (Section 17, Q1); it can be expanded later without changing core
functional behavior. This document does not fix the list.

### 4.4 Workflow W1 — Configure a source

- **Trigger:** The user adds, enables, edits, replaces, or removes a source in
  the web interface.
- **Preconditions:** Signal Engine is running. No authentication is required.
- **Main flow:**
  1. The user opens source configuration.
  2. The user provides or edits the source's functional configuration.
  3. Signal Engine validates that the configuration is well-formed.
  4. Signal Engine saves the source and marks it enabled unless the user chose
     otherwise.
  5. The source becomes eligible for collection (W3), with no change to any other
     part of the system.
- **Expected result:** The source appears in the source list with state
  "enabled" and no collection performed yet.
- **Alternative / error cases:**
  - *Malformed configuration:* rejected with an explanation; nothing is saved.
  - *Source not reachable at configuration time:* whether reachability is checked
    is an open question (Section 17, Q2); if checked, the user is warned but may
    still save.
  - *Duplicate source:* Signal Engine informs the user and does not create a
    second entry; the definition of "same source" is an open question
    (Section 17, Q2).
  - *Disable instead of remove:* disabling stops future collection but keeps
    information already collected from that source.
  - *Remove or replace a source:* the effect on already-collected information is
    an open question (Section 17, Q3).

---

## 5. Interests Configuration

### 5.1 Purpose

The six **areas of interest** (`docs/01-product-spec.md` Section 1.1) are
predefined and are used only to organize the user's interests. They are **not**
six separate processing engines. Signal Engine already understands them; the user
does not need to define anything for monitoring to work.

Within an area, the user **may optionally** add more specific **interests** to
refine what counts as relevant. Configuration is intentionally simple; there is
no personalization system beyond this.

### 5.2 What the user can do with interests

- **Add** one or more interests within an area, expressed in natural language.
- **Edit** an interest.
- **Enable or disable** an interest.
- **Remove** an interest.
- **View** the six areas and any configured interests.

> The exact form of an interest (free text, keywords, a short description) and
> whether a whole area can be disabled are open questions (Section 17, Q6).

### 5.3 Workflow W2 — Configure interests

- **Trigger:** The user opens interests configuration.
- **Preconditions:** Signal Engine is running.
- **Main flow:**
  1. The user reviews the six areas of interest.
  2. The user optionally adds or edits an interest within an area.
  3. Signal Engine saves the interest.
  4. The interest applies to information processed after it is saved.
- **Expected result:** Monitoring is active for the six areas with or without
  interests; any interest appears under its area and is used by relevance
  assessment from that point on.
- **Alternative / error cases:**
  - *Empty interest:* rejected with an explanation.
  - *No interests configured:* Signal Engine still assesses relevance against the
    six areas; interests only sharpen relevance.
  - *Interest changed after signals exist:* existing signals are not deleted;
    whether previously processed information is re-evaluated is an open question
    (Section 17, Q7).

---

## 6. Collection

### 6.1 Purpose

**Collection** is the automated gathering of new raw information items from
enabled sources. From the user's perspective it runs without manual effort after
configuration.

### 6.2 Workflow W3 — Automated collection

- **Trigger:** Signal Engine collects from enabled sources on a recurring basis.
  Whether a manual "collect now" trigger exists, and the collection cadence, are
  open questions (Section 17, Q8, Q9).
- **Preconditions:** At least one enabled source; Signal Engine is running.
- **Main flow:**
  1. Signal Engine contacts each enabled source.
  2. It retrieves items that are new since the last successful collection for
     that source.
  3. Each retrieved item is recorded as a **raw information item** with its
     source and collection time.
  4. Raw information items are handed to processing (Section 7).
  5. The outcome of the collection for each source (success with item count, or
     failure with reason) is recorded for system activity (Section 15).
- **Expected result:** New raw information items are available for processing,
  each carrying its source and collection time.
- **Alternative / error cases:**
  - *No new information:* the collection succeeds with zero new items; normal.
  - *Source unreachable or failing:* that source's collection fails; the failure
    and reason are recorded and shown in system activity; other sources are
    unaffected; Signal Engine retries on the next scheduled collection (retry
    policy: Section 17, Q10).
  - *Partial collection:* items retrieved before a failure are kept and
    processed; the failure is still recorded.
  - *Same item seen again later:* handled by duplicate detection (Section 7).

---

## 7. Processing

### 7.1 Purpose

**Processing** turns raw information items into normalized, de-duplicated
information, decides what is relevant and what is a signal, and preserves
provenance throughout.

### 7.2 Processing steps (functional view)

For each raw information item, Signal Engine functionally:

1. **Normalizes** the item into a consistent internal form (Section 7.3).
2. **Checks for duplication** against what it already knows (Section 7.4).
3. **Assesses relevance** to the user's interests, and tags the item with the
   area(s) of interest it relates to (Section 7.5).
4. **Decides whether the item is important enough to become a signal**
   (Section 7.6).
5. **Summarizes** each signal (Section 8).
6. **Raises an alert** when a new signal is important enough (Section 11).

The order is functional, not an implementation constraint.

### 7.3 Normalization

Normalization must at minimum:

- Extract the item's textual content in a consistent form.
- Preserve the item's **source**, its **original reference / link** where
  available, and its **collection time**.
- Preserve the item's **original publication time** where available.
- Preserve enough metadata to support later search, filtering, and provenance
  display.

The exact metadata set is defined later and is functionally open only where it
affects visible behavior (Section 17, Q11).

### 7.4 Duplicate and near-duplicate handling

- A **duplicate** is an item whose content is effectively the same as one already
  known.
- A **near-duplicate** reports the same underlying story or fact with different
  wording, length, or source.

Functional expectations:

- A duplicate does not create a second entry for the user to review.
- A near-duplicate is grouped with the existing item, not presented as a separate
  signal.
- Signal Engine preserves the fact that **multiple sources reported the same
  information**, keeping each source reference, and the user can see and reach
  each one.

> The precise criteria for duplicate vs near-duplicate, and whether the user can
> adjust sensitivity, are open (Section 17, Q12).

### 7.5 Relevance assessment

- Relevance assessment compares the item against the six areas of interest and
  the user's optional interests, using AI.
- Its output distinguishes **relevant** from **not relevant** (noise).
- It records the **area(s) of interest** the item relates to. An item may relate
  to more than one area.
- It produces a short **reason** that can be shown to the user.
- It keeps facts taken from the source distinguishable from any interpretation
  the AI adds, and never frames its output as advice.

Items assessed as not relevant are **set aside as noise**, not destroyed, so the
decision stays auditable. They are not shown in the normal signal view and do not
trigger alerts. Whether the user can browse not-relevant items is an open
question (Section 17, Q13).

### 7.6 Signal decision

- Only **relevant** information is considered.
- The decision is a single functional question: **is this information important
  enough to bring to the user's attention as a signal?**
- Relevance and importance are distinct: not everything relevant is a signal.

> This document does **not** define how importance is judged — no scoring
> algorithm, threshold, ranking model, trend or opportunity detection, or
> forecasting is specified. The selection criteria remain an open functional
> question (`docs/01-product-spec.md` Section 12; Section 17, Q4).

### 7.7 Workflow W4 — Process an item

- **Trigger:** A raw information item becomes available from collection.
- **Preconditions:** The item has a recorded source and collection time.
- **Main flow:**
  1. Signal Engine normalizes the item.
  2. It checks for duplication; a duplicate is attached to the existing item /
     group and processing stops.
  3. It assesses relevance and tags the area(s) of interest. Not-relevant items
     are set aside as noise.
  4. Relevant items become **relevant information** and are retained in the
     knowledge base.
  5. Where warranted, the item becomes a **signal**.
  6. A **summary** is produced for each signal.
  7. The processing outcome is recorded for system activity (Section 15).
- **Expected result:** The item is discarded as a duplicate, set aside as not
  relevant, or retained as relevant information (possibly a signal) — always with
  its provenance preserved.
- **Alternative / error cases:**
  - *Normalization fails:* the item is marked failed, retained with its
    provenance, and shown in system activity; it does not become a signal.
  - *An AI step fails or is unavailable:* the item is marked pending or failed
    and can be retried; it is not silently dropped (Section 15).
  - *Item has no usable content:* recorded as not relevant / unprocessable, with
    a reason.
  - *Item is not in English:* it is set aside and not processed into a signal; it
    is not silently discarded (Section 15.3).

---

## 8. Summaries

### 8.1 Purpose

Let the user understand a signal without opening the original, while keeping the
link to that original.

### 8.2 Functional expectations

- Signal Engine produces a **concise summary** for each signal.
- A summary is **grounded in the source content** and must not introduce facts
  the source does not support.
- A summary is shown **together with references to its source(s)**.
- Generated interpretation, if any, is distinguishable from facts drawn from the
  source. A summary never contains advice or a recommendation to act.
- Summaries are in English for the MVP.
- Summary length and form are not specified here; a concise summary sufficient
  for triage is the expectation (Section 17, Q14).

### 8.3 Workflow W5 — Summary produced and read

- **Trigger:** A signal is created.
- **Preconditions:** The underlying content was successfully normalized.
- **Main flow:**
  1. Signal Engine generates a summary from the item's content.
  2. It attaches the summary to the signal, with source references.
  3. The user reads the summary during review (W6) or via search (W9).
- **Expected result:** The user can triage the signal from the summary and open
  the original if needed.
- **Alternative / error cases:**
  - *Summarization fails or is unavailable:* the signal is still shown with its
    other information; the summary is marked pending or failed and can be
    retried.
  - *Content too short to summarize:* the original content may be shown directly.

---

## 9. Signal Review

### 9.1 Purpose

Let the user review what Signal Engine found, understand why, and reach the
original sources.

### 9.2 Signal content (functional view)

A signal presented to the user includes at least:

- A **title / headline**.
- The **area(s) of interest** it relates to.
- A **summary** (Section 8).
- The **reason it is a signal** (from Section 7).
- Its **source(s)**, each with an original reference / link where available.
- Relevant **timestamps** (original publication time where available, collection
  time, signal creation time).
- Its current **state** (Section 9.3).
- Any **feedback** the user has given (Section 10).

### 9.3 Signal states (functional)

| State | Meaning |
|-------|---------|
| **New** | Created by processing; not yet reviewed. |
| **Reviewed** | The user has seen the signal. |
| **Kept** | The user marked the signal relevant. |
| **Dismissed** | The user marked the signal not relevant. |

> Whether "Reviewed" is tracked automatically, and whether dismissed signals are
> hidden or kept, are open questions (Section 17, Q15). Signals are retained as
> knowledge regardless of state unless a decision says otherwise.

### 9.4 Functional expectations

The user can:

- See a **list of signals**. Default ordering is an open question (Section 17,
  Q16); newest-first is a reasonable expectation.
- **Filter** signals by at least: area of interest, state, source, and time
  period.
- **Open a signal** to see its full content.
- **Navigate to each original source** where a reference / link exists.
- See that a signal is **corroborated by multiple sources** when applicable.
- Give **feedback** on a signal (Section 10).

### 9.5 Workflow W6 — Review signals

- **Trigger:** The user opens the signal list, often after an alert.
- **Preconditions:** At least one signal exists.
- **Main flow:**
  1. The user opens the signal list.
  2. The user optionally filters or sorts.
  3. The user opens individual signals, reads the summary and reason, and opens
     sources.
  4. The user optionally gives feedback (W7).
- **Expected result:** The user understands the current signals and has followed
  up on those that matter.
- **Alternative / error cases:**
  - *No signals yet:* an explicit empty state explains that collection /
    processing may not have produced signals yet.
  - *A source link is dead:* Signal Engine still shows the stored reference and
    any retained content, marked as not confirmed reachable. Whether link health
    is checked is an open question (Section 17, Q17).
  - *Summary pending:* the signal shows the available information and marks the
    summary as pending.

---

## 10. Feedback on Signals

### 10.1 Purpose

Capture the user's judgement on signals so it can inform how relevance is
assessed over time. Feedback is in scope for the MVP; the exact mechanism is
defined later.

### 10.2 Functional expectations

- The user can mark a signal as **relevant** or **not relevant**.
- Feedback is **attributed to the signal** and retained.
- Feedback updates the signal's state (Section 9.3).
- Feedback is visible in the signal's detail view.
- Feedback **may** inform future relevance assessment. It **must not** be used
  for automated model training or fine-tuning (`docs/01-product-spec.md`
  Section 9).

> Whether feedback can be changed or withdrawn, whether free-text comments are
> supported, and how (or whether) feedback affects future relevance assessment
> are open questions (Section 17, Q18).

### 10.3 Workflow W7 — Give feedback

- **Trigger:** The user chooses to give feedback on a signal during review.
- **Preconditions:** The signal exists and is visible.
- **Main flow:**
  1. The user selects relevant or not relevant.
  2. Signal Engine records the feedback against the signal, with a timestamp.
  3. Signal Engine updates the signal's state.
  4. Signal Engine acknowledges the feedback.
- **Expected result:** The feedback is stored and visible; the signal's state
  reflects it.
- **Alternative / error cases:**
  - *User changes their mind:* subject to Section 17 (Q18); if supported, the
    latest feedback supersedes the previous one.
  - *Feedback cannot be saved:* Signal Engine informs the user and does not
    silently discard the action.

---

## 11. Alerting

### 11.1 Purpose

Notify the user when an important new signal appears, so the user does not have to
keep checking the interface. Alerting is deliberately **simple**. The notification
channel is not decided (`docs/01-product-spec.md` Section 9).

### 11.2 Functional expectations (MVP)

- Alerting can be **globally enabled or disabled** — one global setting.
- An alert is generated **only for a new signal** important enough to warrant
  attention.
- **Duplicates and not-relevant items do not generate alerts.**
- One alert corresponds to one qualifying new signal and lets the user reach that
  signal in the review interface.
- Signal Engine keeps a **record of alerts** raised.
- **No per-area, per-interest, or per-source alert configuration.**
- **No complex alert scoring.** What makes a signal "important enough" is part of
  the open signal-decision criteria (Section 17, Q4).

### 11.3 Workflow W8 — Alert raised and acted upon

- **Trigger:** A new signal is created that is important enough to warrant
  attention.
- **Preconditions:** Alerting is globally enabled.
- **Main flow:**
  1. Signal Engine records the alert and associates it with the new signal.
  2. Signal Engine notifies the user (channel undecided).
  3. The user follows the alert to the signal in the review interface (W6).
- **Expected result:** The user is made aware of the signal; the alert is
  recorded.
- **Alternative / error cases:**
  - *Alerting disabled:* no notification is sent; the signal still appears in the
    review interface.
  - *Notification delivery fails:* the failure is recorded and visible in system
    activity; the signal remains available for review (retry behavior: Section
    17, Q20).
  - *Corroborating item added to an existing signal:* no new alert — it is not a
    new signal.

---

## 12. Knowledge Search

### 12.1 Purpose

Let the user find previously collected knowledge by meaning, not only by exact
keywords.

### 12.2 Functional expectations

- The user enters a query in natural language.
- Signal Engine returns knowledge-base entries ranked by **semantic relevance**.
- The user can **filter** results by basic metadata: area of interest, source,
  time period, and state where applicable.
- Each result shows enough context to be understood (title, summary, area(s),
  source(s), timestamps) and links to the original source(s).
- Search covers **relevant information and signals** retained in the knowledge
  base.
- **Reranking is not an MVP requirement** (`docs/01-product-spec.md` Section 8).

### 12.3 Workflow W9 — Search the knowledge base

- **Trigger:** The user submits a search query.
- **Preconditions:** The knowledge base contains at least one retained entry.
- **Main flow:**
  1. The user enters a query and optional filters.
  2. Signal Engine returns a ranked list of matching entries.
  3. The user opens entries and follows links to sources.
- **Expected result:** The user finds relevant knowledge and can trace each
  result to its source.
- **Alternative / error cases:**
  - *No matches:* an explicit empty state; the query and filters are preserved.
  - *Knowledge base empty:* the interface explains that nothing has been
    collected / processed yet.
  - *Search unavailable:* the user is told search is temporarily unavailable
    (Section 15).

---

## 13. Question Answering

### 13.1 Purpose

Let the user ask a natural-language question and receive an answer built from the
collected knowledge and **always accompanied by its sources**. This is a way to
query collected knowledge — **not** a general-purpose chatbot.

### 13.2 Functional expectations

- The user asks a question in natural language.
- Signal Engine retrieves relevant knowledge-base content, constructs an answer
  from it, and returns the answer **with citations to the specific sources used**.
- The answer must be **grounded in retrieved content**; the AI is not the source
  of truth (`docs/01-product-spec.md` Section 3).
- If retrieved content does not support an answer, Signal Engine says so rather
  than inventing one.
- The answer reports what the sources say; it does **not** give financial, legal,
  or investment advice and does **not** recommend an action or predict an
  outcome, even if the question asks for one.
- Answers are in English for the MVP.
- The user can open each cited source.
- Reranking is not part of the MVP flow.
- Whether prior turns of a conversation are retained is an open question
  (Section 17, Q21). Multi-turn conversational memory is not assumed.

### 13.3 Workflow W10 — Ask a question

- **Trigger:** The user submits a question.
- **Preconditions:** The knowledge base contains retained content.
- **Main flow:**
  1. The user submits a natural-language question, optionally with filters.
  2. Signal Engine retrieves relevant knowledge and constructs an answer.
  3. Signal Engine returns the answer with citations to the sources used.
  4. The user can open each cited source to verify the answer.
- **Expected result:** The user receives a source-grounded answer they can
  verify, or a clear statement that the knowledge base lacks enough information.
- **Alternative / error cases:**
  - *No supporting knowledge:* Signal Engine returns "not enough information in
    the knowledge base".
  - *Partial support:* Signal Engine answers only the supported part.
  - *AI or retrieval unavailable:* the user is told answering is temporarily
    unavailable; no fabricated answer.
  - *Question asks for advice or a prediction:* Signal Engine answers with the
    relevant source-grounded information and states that the decision is the
    user's.
  - *Question unrelated to the knowledge base:* Signal Engine indicates it can
    only answer from its knowledge base.

---

## 14. Source and Provenance Behavior

### 14.1 Purpose

Guarantee that every user-facing output can be traced back to the information that
supports it. This is a defining product property (`docs/01-product-spec.md`
Section 3).

### 14.2 Functional rules

- Every **raw information item** retains its **source** and **collection time**.
- Every **relevant information** entry and **signal** retains the **original
  reference / link where available** and the **source(s)** it came from.
- Every **summary** is shown with references to the source content it summarizes.
- Every **answer** is shown with citations to the specific sources used.
- When multiple sources corroborate the same information, **all** contributing
  source references are retained and shown.
- Facts taken from a source are distinguishable from any interpretation added by
  the system. No user-facing output is framed as advice.
- Signal Engine does not present information whose provenance it cannot show.
- If an original reference later becomes unreachable, the stored reference and
  any retained content are still shown, marked as not confirmed reachable
  (Section 17, Q17).

Provenance is kept simple: the original source, its link where available, and
enough information for the user to verify it.

---

## 15. System Activity, Failures, and Source Language

### 15.1 Basic system activity

Signal Engine should be understandable rather than a black box, but the MVP keeps
this **basic** — only what is needed to see what happened and what failed. A
dedicated AI-evaluation subsystem or an observability dashboard is anticipated
later (`docs/01-product-spec.md` Section 8), not part of the MVP.

The user can see, at a level appropriate to a personal user:

- **Collection:** per source, the last collection time, its outcome, the number
  of new items, and any error.
- **Processing:** how many items were retained as relevant, set aside as noise,
  de-duplicated, or failed — and the failures.
- **Alerts:** which alerts were raised and whether delivery succeeded.
- **Failures:** what failed, when, and why, with enough context to identify the
  source or item involved.

How long activity history is retained is an open question (Section 17, Q22).

### 15.2 Error and failure behavior

Principles:

- Signal Engine **does not silently drop** information or user actions on
  failure.
- Failures are **recorded** and **surfaced** in system activity.
- User-facing error messages are in **clear English** and state what failed and,
  where possible, what the user can do.
- A failure in one part (one source, one item, one AI step) **does not stop**
  unrelated processing.
- Signal Engine never fabricates a result to hide a failure — no invented
  answers, summaries, or signals.

| Failure | User-visible behavior |
|---------|-----------------------|
| A source is unreachable | Recorded per source; other sources continue; retried on the next collection (policy: Section 17, Q10). |
| An item cannot be normalized | Marked failed, retained with provenance, shown in activity; excluded from signals until reprocessed. |
| An item is not in English | Set aside, retained with provenance, not processed into a signal, not discarded (Section 15.3). |
| An AI step fails or is unavailable | Affected item / answer marked pending or failed; no fabricated output; retry possible. |
| Summarization fails | Signal shown without a summary, marked pending / failed. |
| Search unavailable | User told search is temporarily unavailable; query preserved. |
| Question answering unavailable | User told answering is temporarily unavailable; no fabricated answer. |
| Alert delivery fails | Recorded in activity; the signal remains available for review (policy: Section 17, Q20). |
| Feedback cannot be saved | User told the action failed; feedback not silently lost. |
| Signal Engine is down | The web interface is unavailable; on restart, in-progress work resumes or is retried (Section 17, Q23). |

### 15.3 Source language

- For the MVP, Signal Engine focuses on **English-language source content**.
- Non-English source content is **not processed into signals** and is **not
  silently discarded** — the user can see that such an item was collected and not
  processed.
- **Machine translation is not an MVP feature.**
- The product must remain **open to additional source languages later** without
  rewriting collection, processing, signals, search, question answering, or
  provenance behavior.

The project's code, documentation, and UI terminology are always in English
regardless of source language.

Future multilingual behavior (which languages, in what order, and the language of
summaries and answers for non-English sources) is an open question (Section 17,
Q24).

### 15.4 Workflow W11 — Understand and recover from a failure

- **Trigger:** The user notices missing signals, a stale source, or an error
  message.
- **Preconditions:** Signal Engine has recorded activity.
- **Main flow:**
  1. The user opens the activity view and filters to failures.
  2. The user identifies the failed source or item and the reason.
  3. The user takes a corrective action available in the MVP (for example, fix a
     source's configuration or re-enable a source).
- **Expected result:** The user understands the failure and can act on what is
  within their control.
- **Alternative / error cases:**
  - *No manual retry control for a given failure:* the failure is still visible;
    Signal Engine retries on its normal schedule. Which manual retry controls
    exist is an open question (Section 17, Q25).

---

## 16. Functional Rules and Constraints

| ID | Rule |
|----|------|
| R1 | Every user-facing output (signal, summary, search result, answer) shows its source(s); Signal Engine does not present information whose provenance it cannot show. |
| R2 | Answers must be grounded in retrieved knowledge-base content; when content does not support an answer, Signal Engine says so rather than answering anyway. |
| R3 | The AI is never treated as the source of truth. |
| R4 | Facts drawn from a source are distinguishable from interpretation added by the system. |
| R5 | Noise and duplicate items are set aside, not permanently destroyed, so decisions remain auditable. |
| R6 | Alerts are raised only for a **new signal** important enough to warrant attention — never for not-relevant items, duplicates, or updates to an existing signal. Alerting has a single global enable/disable, no per-area / per-interest / per-source configuration, and no complex scoring. |
| R7 | Disabling a source stops future collection but retains information already collected from it. The effect of **removing** a source on already-collected information is undecided (Section 17, Q3). |
| R8 | User feedback is retained and attributed to the signal; it must not be used for automated model training or fine-tuning in the MVP. |
| R9 | No information or user action is silently dropped on failure; failures are recorded and surfaced in system activity. |
| R10 | A failure affecting one source, item, or AI step must not halt unrelated processing. |
| R11 | Signal Engine must not fabricate a result to mask a failure. |
| R12 | The user interface, terminology, system messages, code, and documentation are always in English; source content of English items is shown in its original form. |
| R13 | For the MVP, Signal Engine focuses on English-language source content; non-English content is not processed into signals and is never silently discarded. Adding a source language later must not require rewriting product behavior. |
| R14 | The MVP has exactly one user role and no authentication; nothing in the functional design may assume multiple users. |
| R15 | Areas of interest, interests, and sources are independently configurable; a change to one must not require reconfiguring another. |
| R16 | Changes to interests or sources apply to information processed after the change; retroactive re-processing is undecided (Section 17, Q7). |
| R17 | Every signal records why it was selected and which area(s) of interest it relates to. |
| R18 | The user can always reach the signal referenced by an alert through the review interface, regardless of the notification channel. |
| R19 | Reranking is not an MVP requirement and must not be presented as one. |
| R20 | Monitoring covers exactly the six areas of interest; no additional area is added without an explicit decision. An information item may relate to more than one area. |
| R21 | A signal is simply a relevant and/or important piece of information worth bringing to the user's attention. There is no signal taxonomy and no signal-kind model. |
| R22 | Sources are curated and reliable, and are configuration data, not business logic. Adding, editing, enabling, disabling, replacing, or removing a source must not require changing unrelated collection, processing, relevance, signal, summary, alerting, search, or question-answering behavior. There is no source-authority scoring or weighting model. |
| R23 | This specification does not define the signal-selection algorithm. "Relevant, then important enough to surface" is the functional principle; any threshold, scoring, ranking, or detection mechanics remain open (Section 17, Q4). |
| R24 | For the MVP, Signal Engine collects from a curated set of sources; it is not unrestricted or open-ended Internet monitoring. |
| R25 | Signal Engine presents information; it never gives financial, legal, or investment advice, never recommends an action, and never promises returns, opportunities, or outcomes. No user-facing output is framed as advice. |
| R26 | AI evaluation and a full observability subsystem are anticipated later, not part of the MVP; the MVP provides only basic activity and failure visibility. |

---

## 17. Open Functional Questions

These require a decision before or during the technical specification. They are
**not** resolved in this document.

| ID | Question | Impact |
|----|----------|--------|
| Q1 | Which concrete **source types** does the MVP support (e.g. web feeds, article pages, newsletters, official registers), and what is the final curated source list? | Shapes source configuration, collection, and normalization. |
| Q2 | Does source configuration perform a **reachability check**, and how is "the same source" defined for duplicate-source detection? | Affects W1 validation and error handling. |
| Q3 | When a source is **removed or replaced**, what happens to information already collected from it (kept, marked orphaned, or deleted)? | Affects knowledge-base integrity and provenance (R7). |
| Q4 | What are the **signal-selection criteria** — how is "relevant" judged, and how is "important enough to surface" judged? (No scoring algorithm, threshold, trend or opportunity detection, or forecasting is to be invented.) | Central to relevance assessment, the signal decision, and alerting. |
| Q5 | Can a source be associated with **specific interests or areas**, or is every enabled source evaluated against all of the user's interests? | Affects interests configuration and relevance assessment. |
| Q6 | Can the user **disable a whole area of interest**, and what is the exact **form of an interest** (free text, keywords, short description)? | Affects interests configuration (Section 5). |
| Q7 | When interests or sources change, is **previously processed information re-evaluated**? | Affects consistency of the knowledge base (R16). |
| Q8 | Does the MVP provide a **manual "collect now"** trigger in addition to scheduled collection? | Affects the collection workflow and UI. |
| Q9 | What is the **collection cadence**, and is it user-configurable? | Affects freshness and load. |
| Q10 | What is the **retry / backoff policy** for a failing source? | Affects reliability and activity reporting. |
| Q11 | What is the **minimum visible metadata set** for an information item / signal (beyond source, link, timestamps, and area of interest)? | Affects review, search filters, and provenance display. |
| Q12 | What are the **criteria for duplicate vs near-duplicate**, and can the user adjust sensitivity? | Affects noise reduction quality and grouping. |
| Q13 | Can the user **browse not-relevant (noise) items** and correct misclassifications? | Affects transparency and feedback. |
| Q14 | What is the expected **summary form / length**, and is it configurable? | Affects triage usefulness. |
| Q15 | What is the exact **signal lifecycle** — is "Reviewed" tracked automatically, and are dismissed signals hidden or kept? | Affects review UX and retention. |
| Q16 | What is the **default ordering** of the signal list? | Affects review efficiency. |
| Q17 | Does Signal Engine **check the health of original source links**, and how is a dead link presented? | Affects provenance display (R1, Section 14). |
| Q18 | Can feedback be **changed or withdrawn**, are **free-text comments** supported, and how (if at all) does feedback affect **future relevance assessment**? | Defines the feedback mechanism left open by `01-product-spec.md`. |
| Q19 | What **notification channel(s)** does MVP alerting use to reach the user? (The rest of MVP alerting is decided and simple — Section 11.) | Completes alerting behavior. |
| Q20 | What is the **retry policy for failed alert delivery**? | Affects reliability of notifications. |
| Q21 | Does question answering support **multi-turn context**, or is each question independent? | Affects the question-answering UX; the product is explicitly not a chatbot. |
| Q22 | How long is **system activity history retained**, and is old activity purged? | Affects visibility and storage. |
| Q23 | On restart after Signal Engine is down, does in-progress work **resume** or is it **restarted / retried**? | Affects reliability guarantees. |
| Q24 | **Future** source-language behavior (the MVP is English-focused): which additional source languages are supported and when; can the user restrict monitored languages; in which language are summaries and answers produced for non-English sources. | Affects a later multilingual extension only. |
| Q25 | Which **manual retry / reprocessing controls** does the MVP expose to the user? | Affects failure recovery UX (W11). |

---

## 18. Alignment with the Product Specification

This functional specification is consistent with `docs/01-product-spec.md`:

- The central objective is "help one user stay up to date on the areas they care
  about"; the functional flow (Section 3) matches the product's core MVP idea.
- A **signal** is the simple concept from the product spec — a relevant and/or
  important piece of information worth surfacing. No signal taxonomy is
  introduced (R21).
- **Sources** are reliable, curated, configurable, and replaceable, with
  mandatory provenance and original links (Section 4, Section 14). No
  source-authority taxonomy or weighting is introduced (R22).
- The **six areas of interest** are kept, used only to organize interests, not as
  separate engines (Section 5, R20).
- **Feedback** and **alerting** are in scope; the notification channel is left
  open, matching the product spec.
- **Search** and **question answering** are in scope and grounded in stored
  sources; question answering is explicitly not a general-purpose assistant.
- **AI evaluation** and a full **observability** subsystem are treated as
  anticipated-later concerns (R26); only basic activity and failure visibility is
  in the MVP.
- **Forecasting, prediction, trend algorithms, correlation, opportunity scoring,
  and recommendation systems** are out of scope (Section 2.2, R23, R25).
- No technologies, frameworks, databases, APIs, protocols, agents, or
  architecture are defined here; those remain for the technical specification.
