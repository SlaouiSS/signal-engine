# Signal Engine — Product Specification

Document ID: `01-product-spec.md`
Status: Draft — awaiting review
Scope: Product requirements only. This document does not define the technical
stack, frameworks, database technology, AI models or providers, agent design,
communication protocols, or software architecture. Those belong to later
specifications.

---

## 1. What Signal Engine Is

Signal Engine is an open-source, AI-powered **personal monitoring platform**. Its
job is to help **one person stay up to date** on the topics they care about,
without reading every source by hand.

Signal Engine collects information from a set of reliable, curated sources,
removes obvious noise and duplicates, uses AI to identify what is relevant to the
user's interests and important enough to surface, produces a short summary of
each such item while keeping its original source link, and presents the result to
the user as **signals**. Collected knowledge is stored so the user can search it
later, and the user is alerted when an important new signal appears.

A **signal** is simply: a piece of information that is relevant to the user's
interests and/or important enough to bring to their attention. The concept is
deliberately kept simple at this stage.

Signal Engine is **not a chatbot**, and it is **not** a prediction, forecasting,
or opportunity-analysis platform. It does not give financial, legal, or
investment advice and makes no promise of outcomes. It surfaces information; the
user decides what to do with it.

### 1.1 Areas of Interest

The user's interests span the following areas. These are broad areas of interest,
not separate product features or engines.

1. **AI & Technology**
2. **Markets & Investment**
3. **Architecture, Construction & Real Estate**
4. **Law & Regulation**
5. **Fashion & Clothing**
6. **Business & Opportunity Trends**

Within these areas the user defines more specific interests during configuration.
Adding an area beyond these six requires an explicit decision.

The transformation Signal Engine performs:

```
Large amount of raw information
            ↓
    Processing and analysis
            ↓
    Relevant information
            ↓
        Signals
            ↓
    Searchable knowledge
```

---

## 2. The Problem It Solves

Following several fast-moving areas at once produces far more incoming
information than one person can read, and most of it does not matter for any
given interest.

- Relevant information is scattered across many sources.
- The same story is republished many times, creating duplicates and
  near-duplicates.
- Most incoming items are noise relative to a given interest.
- Reading, filtering, and summarizing sources by hand is slow and repetitive.
- Knowledge gathered over time is rarely kept in a searchable form.
- Naive AI summaries invent facts and lose the link back to the original source.

Signal Engine addresses this by automating collection, noise and duplicate
removal, relevance and importance assessment, and summarization — and by keeping
the result as source-linked, searchable knowledge.

---

## 3. Main Objective

**Help one user stay up to date on the areas they care about.**

Turn a large, continuous stream of raw information into a smaller set of
relevant, de-duplicated, summarized signals, each keeping its original source
link, and retain them as knowledge the user can search later.

Two properties are treated as defining the product:

1. **Source grounding.** Every signal, summary, and answer can be traced back to
   the information that supports it and links to the original source. The AI is
   not treated as the source of truth.
2. **Changeability.** The product must stay easy to understand, extend, and
   reconfigure over time — for example, adding or replacing sources, interests,
   or the underlying AI.

Signal Engine **presents** signals. The final judgement always belongs to the
user. It does not recommend actions or promise outcomes.

---

## 4. Target User

The target user is a **single individual running Signal Engine for personal
use** — someone who wants to stay up to date across several areas at once and
build a searchable knowledge base without reading every source by hand.

Because the project is open source, a secondary audience is **developers and
contributors** who run, configure, extend, or study the platform.

> Confirmation needed — see Section 12.

---

## 5. Main Use Cases

### 5.1 Configure monitoring
The user defines the sources to collect from and the interests, within the areas
in Section 1.1, that determine what counts as relevant.

### 5.2 Automated collection and processing
The platform collects new information from the configured sources, normalizes it
into a consistent form, and removes obvious noise and duplicate or near-duplicate
items — without the user doing this manually.

### 5.3 Relevance and importance assessment
The platform uses AI to classify incoming information, judge whether it is
relevant to the user's interests, and judge whether it is important enough to
surface as a signal.

### 5.4 Summarization
The platform produces a concise summary of each signal, so the user can
understand it without opening the original, while keeping the link to that
original.

### 5.5 Review signals
The user reviews the signals: what was found, why it is considered relevant or
important, and where it came from.

### 5.6 Provide feedback on signals
The user can mark a signal as relevant or not relevant, so their judgement is
captured and can inform how relevance is assessed over time. The exact mechanism
is defined later.

### 5.7 Be alerted to important new signals
The platform alerts the user when an important new signal appears, so the user
does not have to keep checking the interface. The alert channels are defined
later.

### 5.8 Search the knowledge base
The user searches the accumulated knowledge by meaning, optionally narrowed by
simple metadata such as source or date.

### 5.9 Ask a question
The user can ask a natural-language question and receive an answer built from the
stored knowledge and returned **with its sources**. This is a way to query
collected knowledge, not a general-purpose assistant.

---

## 6. Core Capabilities

The order does not imply implementation order.

| # | Capability | Description |
|---|------------|-------------|
| 1 | Source configuration | Configure the sources to collect from; sources are replaceable without changing product behavior. |
| 2 | Interest configuration | Configure the user's interests within the six areas. |
| 3 | Ingestion | Collect information from the configured sources. |
| 4 | Normalization | Convert collected information into a consistent internal form. |
| 5 | Deduplication | Detect and remove duplicate and near-duplicate items. |
| 6 | Noise removal | Filter out information not relevant to the user's interests. |
| 7 | Relevance & importance assessment | Use AI to decide what is relevant and important enough to surface as a signal. |
| 8 | Classification | Categorize information by area of interest. |
| 9 | Summarization | Produce a concise summary of each signal. |
| 10 | Source linking | Keep the original source link on every signal, summary, and answer. |
| 11 | Knowledge storage | Retain processed information, signals, and their metadata. |
| 12 | Semantic search | Find stored knowledge by meaning, with optional metadata filtering. |
| 13 | Question answering | Answer natural-language questions from stored knowledge, with sources. |
| 14 | Source-grounded output | Distinguish facts taken from sources from generated interpretation; avoid unsupported claims. |
| 15 | Feedback on signals | Capture the user's relevant / not-relevant judgement. |
| 16 | Alerting | Alert the user when an important new signal appears. |
| 17 | AI flexibility | Allow the underlying AI model and provider to be changed without rewriting product behavior. |

Source language: for the MVP, source content may be limited to English. Code,
documentation, and UI terminology are always in English. The product must stay
open to additional source languages later.

---

## 7. Expected User Experience (High Level)

- **Set up once, benefit continuously.** After configuring sources and interests,
  the platform keeps collecting and processing without manual effort.
- **Less to read.** The user sees a reduced, de-duplicated set of relevant
  signals instead of a raw firehose.
- **Always traceable.** Every signal, summary, and answer links to its original
  source, and the user can reach that source.
- **The user decides.** Signal Engine surfaces what deserves attention; it does
  not tell the user what to do or promise an outcome.
- **Honest about uncertainty.** Generated interpretation is distinguishable from
  facts taken from sources. Unsupported claims are avoided.
- **Searchable memory.** Knowledge the platform has gathered stays available
  later through search and question-answering.

The frontend is a web interface. Users interact with the platform through that
interface; they never interact with storage directly.

---

## 8. MVP Scope

The MVP is a **personal-use** version of Signal Engine for a single user.

**Areas of interest for the MVP:** the six areas listed in Section 1.1, and no
others.

**In scope for the MVP:**

- Configuration of sources and of the user's interests within the six areas.
- Ingestion from multiple configured sources.
- Normalization of ingested information.
- Deduplication and near-duplicate detection.
- Noise removal / relevance filtering.
- AI classification by area, and relevance and importance assessment.
- Signal identification: promoting relevant and important items into signals.
- A concise summary for each signal, with the original source link preserved.
- Presentation of signals to the user through a web frontend backed by a backend
  API.
- User feedback on signals (relevant / not relevant); the exact mechanism is
  defined later.
- Simple alerting when an important new signal appears; the alert channels are
  defined later.
- Persistent storage of collected information, signals, metadata, and the data
  needed for semantic search.
- Semantic search over the stored knowledge, with metadata filtering.
- Question answering that returns answers with their sources.
- Configuration-based secret management (no hardcoded credentials).
- English-language source content (support for more languages is not required for
  the MVP, but the product must stay open to it).

**Source strategy for the MVP (product level, not a technical source list):**

- Sources are chosen to be **reliable and curated**.
- Every signal keeps a link to its **original source**; source provenance is
  mandatory.
- Sources are **configurable and replaceable** without coupling them to business
  logic.
- The concrete source types and the ingestion mechanism are **deliberately not
  decided here**; they belong to the functional and technical specifications.

**Explicitly deferred (not in the MVP, but anticipated later):**

- AI evaluation of output quality as a separate, measured concern.
- Observability into ingestion, processing, and AI activity beyond basic
  operation.
- Optional reranking within the question-answering flow.
- Source languages beyond English.
- Areas of interest beyond the six in Section 1.1.
- Multi-user support, collaboration, authentication, and authorization.

The project code, documentation, and UI terminology remain in English regardless
of source language.

---

## 9. Explicitly Out of Scope

The following are **not** part of Signal Engine as currently understood. Adding
any of them requires an explicit decision.

- **Forecasting or prediction** of any kind, including financial prediction.
- **Complex trend detection, correlation engines, opportunity scoring, or
  recommendation systems.** Signal Engine identifies relevant and important
  information; it does not model, score, or rank trends and opportunities beyond
  that.
- **Financial, legal, or investment advice**, or any promise of returns,
  opportunities, or outcomes. The user makes the final judgement.
- **Authentication and user accounts** — not required for the initial
  personal-use MVP unless requirements change.
- **Multi-user, roles, permissions, tenancy, sharing, or collaboration.**
- **Acting on the world.** Beyond alerting the user about detected signals,
  Signal Engine does not post content, publish, trigger workflows in other
  systems, or take automated actions.
- **Being a general-purpose chatbot or assistant** unrelated to the collected
  knowledge base.
- **Complex personalization** beyond configured interests and simple
  relevant / not-relevant feedback.
- **Direct database access from the frontend.**
- **Treating the LLM as the source of truth**, or producing answers that are not
  grounded in retrieved/stored information.
- **Solving hallucination through prompt wording alone**, without architectural,
  validation, retrieval, and evaluation controls.
- **Native mobile applications.**
- **A fixed set of notification channels.** Alerting is in scope; which channels
  are supported is not decided here.
- **User feedback used for automated model training or fine-tuning.**
- **Trend analytics, dashboards of metrics over time, or forecasting.**
- **A public API or third-party integration platform** for external consumers.
- **Distributed / microservice operational complexity** introduced without a
  concrete requirement.

---

## 10. Product Principles

1. **Stay up to date.** The product's job is to keep one person current on the
   areas they care about, with as little manual reading as possible.
2. **Signals over volume.** Value is measured by how well the stream is reduced to
   the few items that matter, not by how much is collected.
3. **Signals, not advice.** Signal Engine presents; the user decides. It does not
   recommend actions and makes no promise of returns, opportunities, or outcomes.
4. **The user stays in the loop.** The user can give feedback on signals
   (relevant / not relevant) and is alerted to important new ones rather than
   having to poll the interface.
5. **Source-grounded by default.** Every signal, summary, and answer links to its
   source. The LLM is not the source of truth.
6. **Honest output.** Facts drawn from sources are distinguishable from generated
   interpretation. Unsupported claims are avoided.
7. **Replaceable parts.** Sources, interests, AI models, and AI providers can be
   added, removed, or swapped without rewriting product behavior.
8. **Start simple, grow deliberately.** Capabilities and complexity are added only
   when justified by a concrete need.
9. **Open and understandable.** A developer who did not build Signal Engine can
   understand what it is, why it exists, and how to run and extend it.
10. **English project, extensible source languages.** The code, documentation, and
    UI terminology are always in English. Source content may be limited to English
    for the MVP; the product stays extensible to additional source languages
    later.

---

## 11. High-Level Success Criteria

Signal Engine is successful, at the product level, when:

1. A user can configure sources and interests across the six areas and then
   receive relevant signals without manually reading every source.
2. The set of items presented to the user is meaningfully smaller than the raw
   input, with duplicates and near-duplicates removed and off-topic noise
   filtered out.
3. Identified signals are genuinely relevant to the user's stated interests, and
   each one shows why it was selected and where it came from.
4. Every signal, summary, search result, and answer links to its original
   source, and the user can open that source.
5. Signal Engine presents signals without recommending actions or promising
   returns or outcomes; the user retains the final judgement.
6. The user can mark a signal as relevant or not relevant, and that feedback is
   captured.
7. The user is alerted to important new signals without having to keep checking
   the interface.
8. Summaries let the user understand a signal without opening the original, while
   still linking to it.
9. English-language source content is ingested and processed into usable signals
   and knowledge, and adding another source language later does not require
   rewriting product behavior.
10. Semantic search returns relevant knowledge for meaning-based queries, not just
    exact keyword matches.
11. Question answering is grounded in stored knowledge and always includes its
    sources; the user can verify each answer against those sources.
12. An AI model or provider can be changed without changing product behavior that
    users depend on.
13. A new contributor can run Signal Engine and understand its purpose from the
    repository alone.

> These are directional product criteria. Measurable targets and thresholds are
> not defined at this stage and require a later decision — see Section 12.

---

## 12. Points Requiring Validation

The following are genuinely needed before the functional specification and should
be decided rather than assumed:

1. **Primary user definition.** This document assumes a single individual user
   plus a developer/contributor audience. Confirm this is intended, and whether
   "personal use" means strictly one user on their own machine.

2. **Concrete source types.** The product-level source strategy (reliable,
   curated, replaceable, provenance mandatory) is set. The concrete source
   **types** and ingestion mechanisms are still undecided and belong to the
   functional/technical specification.

3. **Selecting signals.** The concept of a signal is intentionally kept simple.
   What still needs defining is the **selection criteria** — how "relevant" and
   "important enough to surface" are judged in practice.

4. **Interest configuration.** Are the six areas fixed for the MVP, and can the
   user freely add interests within them? How are interests attached to areas?

5. **Supported source language for the MVP.** Confirm the MVP supports
   English-language sources only, and identify which later languages matter most
   for extensibility planning.

6. **Measurable success targets.** No quantitative targets exist yet (for
   example, acceptable relevance precision, deduplication accuracy,
   answer-grounding rate). Confirm whether targets should be set now or after the
   evaluation stage.

### Resolved by user decision

- **User feedback on signals** — IN SCOPE for the MVP. The user must be able to
  mark a signal as relevant or not relevant. The exact feedback mechanism is
  defined later. (2026-09-03)
- **Alerting** — IN SCOPE for the MVP. Signal Engine must alert the user when an
  important new signal appears. The exact alert channels and implementation are
  defined later. (2026-09-03)
- **Source content language** — Support for multiple source languages is **not**
  an MVP requirement. For the MVP, source content may be limited to
  English-language sources. The product must remain **extensible** to additional
  source languages later. Code, documentation, and UI terminology remain in
  English. (2026-09-03)
- **Areas of interest** — The six areas in Section 1.1 are the product's areas of
  interest for the MVP. No additional areas without a decision. (2026-09-03)
- **Source strategy** — Product-level preference for reliable, curated sources
  that are configurable and replaceable, with source provenance always mandatory.
  Concrete source types and ingestion mechanisms remain deferred. Simplified on
  2026-09-04: the earlier three-tier source-authority taxonomy
  (primary/official, specialized, trend/weak-signal) was removed to keep the
  product specification simple; reintroducing an authority distinction would be a
  new product decision.
- **Product concept** — CLARIFIED (2026-09-03), simplified (2026-09-04). Signal
  Engine helps one user stay up to date by presenting relevant and important
  information as signals. It is not an aggregator only, and it is not a
  prediction, forecasting, or opportunity-analysis platform. It does not provide
  financial/legal/investment advice or promise returns or outcomes.
