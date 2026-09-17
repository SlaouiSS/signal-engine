# Signal Engine — AI Agents

Document ID: `06-ai-agents.md`
Status: Accepted — reviewed as part of the Phase 0 documentation baseline
(`docs/11-roadmap.md` Section 3); decisions this document marks open or
provisional remain open or provisional until resolved.
Depends on: `CLAUDE.md`, `docs/01-product-spec.md`, `docs/02-functional-spec.md`,
`docs/03-technical-spec.md`, `docs/04-architecture.md`, `docs/05-data-model.md`

---

## 1. Purpose and Scope

This document defines the **AI-capability architecture** of Signal Engine: which
focused AI capabilities exist, what each is responsible for, what it explicitly
does not do, its inputs and outputs, and how it fits the Java/Python boundary
already established.

It exists because `docs/03-technical-spec.md` Section 7 names the Python
capability set and `docs/03-technical-spec.md` Section 1.3 explicitly defers
"concrete agent prompts, decision rubrics, and per-agent contracts in full" to
this document. This document goes one step further than Section 7's summary
table, at the **conceptual/design** level — not at the level of concrete
prompts, request/response field lists, or code.

This document does **not**:

- define concrete prompts, prompt templates, or prompt wording;
- define exact request/response schemas, field names, or JSON shapes beyond
  what `docs/03-technical-spec.md` Section 7.2 and Section 8 already fix;
- define Python packages, modules, classes, or API endpoints;
- define database tables or columns (`docs/05-data-model.md`);
- define retrieval/chunking strategy for question answering
  (`docs/07-rag.md`);
- define source connectors (`docs/08-ingestion.md`);
- define an AI evaluation subsystem — that is explicitly deferred
  (`docs/01-product-spec.md` Section 8; `docs/03-technical-spec.md`
  Section 16.3);
- resolve any open product, functional, or technical question. Where a
  capability's design depends on one, it is named and left open (Section 14).

Terminology follows `docs/01-05`: **area of interest**, **interest**,
**source**, **raw information item**, **relevant information**, **signal**,
**summary**, **feedback**, exactly as defined there. No new product concept is
introduced.

---

## 2. AI Architecture Principles

These are not new decisions; they are the already-approved principles this
document must honor (`docs/03-technical-spec.md` Section 3, 7; `docs/04-architecture.md`
Sections 5, 8, 17):

1. **Deterministic software for deterministic problems, AI for semantic
   problems.** An AI capability is used only where judgement about meaning is
   required; everything that can be computed by a rule stays in Java
   (`docs/03-technical-spec.md` Section 3.3).
2. **Java owns business state and orchestration.** No AI capability decides
   *when* it runs, *what* runs after it, or *whether* its result is persisted.
3. **Python is a stateless AI service.** Each capability is a pure function of
   its request plus the configured model(s); no capability holds state between
   requests, owns business data, or accesses PostgreSQL
   (`docs/03-technical-spec.md` Section 7.1, 7.4).
4. **Python dependency composition is native Python.** Capabilities are
   composed explicitly, with `typing.Protocol`/`abc.ABC` only where they earn
   their keep; no Python DI/IoC container is used, and no framework is adopted
   to mirror Spring (`docs/03-technical-spec.md` Section 7.1, 9.2;
   `docs/04-architecture.md` Section 5).
5. **AI output is structured, validated, bounded, and advisory.** A capability
   returns a typed result or a typed error; Java validates it before it can
   affect anything persisted, and AI output never mutates business state
   directly (`docs/03-technical-spec.md` Section 3.4, 8.3, 9.4, 9.6).
6. **Source grounding is mandatory** wherever a capability produces
   user-facing or business-relevant content (relevance, importance,
   classification, summarization, question answering)
   (`docs/01-product-spec.md` Section 3; `docs/02-functional-spec.md`
   R2, R4, R25).
7. **Providers and models are replaceable.** No capability, prompt, or
   business rule names a provider or a model; the initial local runtime is
   Ollama with `gpt-oss:20b`, reached only through the project's own provider
   abstraction (`docs/03-technical-spec.md` Section 4.6, 9.1–9.2).
8. **No unnecessary microservices or distributed architecture.** All
   capabilities described here run within the single Python AI service of the
   MVP; splitting is a future, optional evolution, not part of this design
   (`docs/03-technical-spec.md` Section 21.6; Section 13 below).
9. **No new product capability.** Every capability in Section 4 exists to
   serve a step already defined in the approved flow (Section 3 below); no
   capability is added beyond that.

---

## 3. AI Service Boundary

The Python AI service is one deployable unit for the MVP, exposing a small set
of focused capabilities behind explicit, versioned contracts
(`docs/03-technical-spec.md` Section 7.1, 8.1; `docs/04-architecture.md`
Section 5).

```
Java backend                         Python AI service
(owns state, orchestrates,           (stateless, one capability
 schedules, persists)                 per call, no business state)
      │                                       │
      │  synchronous HTTP/JSON,               │
      │  versioned request/response           │
      │  contracts (per capability)           │
      ├──────────────────────────────────────►│
      │                                       │  composes natively,
      │◄──────────────────────────────────────┤  calls the provider
      │  typed result OR typed error           │  abstraction
      ▼                                       ▼
Deterministic decision                LLM / embedding provider
(persist, transition state,           (Ollama initially, behind
 raise alert, etc.)                    LlmChatProvider / EmbeddingProvider)
```

Every capability call in this document assumes this same boundary: **Java
initiates, Python responds once, and the response is advisory input to a
Java-owned decision.** This is stated once here and not repeated for every
capability in Section 4.

The Signal Engine flow this AI service supports, unchanged from
`docs/03-technical-spec.md` Section 10.1:

```
Reliable sources
      → Collection            (deterministic, Java)
      → Normalization         (deterministic, Java)
      → Deduplication         (deterministic + semantic — Section 4.1)
      → Relevance              (AI — Section 4.3, preceded by Section 4.2)
      → Importance / signal decision   (AI assessment — Section 4.4;
                                        deterministic transition — Java)
      → Summary                (AI — Section 4.5)
      → Signal + source link   (deterministic, Java)
      → Search / Question Answering    (AI — Sections 4.6, 4.7)
      → Simple alert           (deterministic, Java)
```

---

## 4. AI Capabilities / Agents

Each capability below is described conceptually. No capability orchestrates
another; each is called independently by the Java backend at the point in the
flow above where it is needed (Section 11). "Agent" and "capability" are used
interchangeably, following `docs/03-technical-spec.md` Section 7's own usage;
this does not imply an autonomous, multi-step, self-directing agent — each is
a single, bounded, request/response capability.

### 4.1 Near-Duplicate Assessment

1. **Purpose:** help distinguish a genuinely new item from one that reports the
   same underlying story as something already known, when the two are not
   byte-identical.
2. **Responsibility:** given a candidate item (or its vector) and comparison
   item(s)/vector(s), produce a similarity signal usable in a near-duplicate
   decision.
3. **Does NOT:**
   - decide whether the item **is** a duplicate — that final decision is a
     deterministic threshold comparison owned by Java
     (`docs/03-technical-spec.md` Section 10.5; `docs/05-data-model.md`
     Section 18);
   - perform exact/hash duplicate detection — that is deterministic Java logic,
     entirely outside this capability (`docs/03-technical-spec.md`
     Section 3.3);
   - group items, merge records, or write anything to persistence.
4. **Inputs:** candidate text (or its embedding) and one or more comparison
   texts or embeddings (`docs/03-technical-spec.md` Section 7.2, `similarity`).
5. **Outputs:** a near-duplicate score (or scores), one per comparison.
6. **Deterministic or AI-generated:** the **similarity computation** may use an
   embedding model (AI-adjacent) or deterministic vector math over already
   computed embeddings; either way, the **decision** that follows is
   deterministic Java logic acting on the score
   (`docs/03-technical-spec.md` Section 7.2, "embedding model / deterministic
   math").
7. **Validation expectations:** the returned score(s) must be well-formed,
   numeric, and within an expected range; malformed output is handled as any
   other structured-output failure (Section 7).
8. **Source/provenance requirements:** not directly applicable — this
   capability compares content, it does not produce user-facing text; the
   grouping and provenance recording that follows a "near-duplicate" decision
   is a Java/data-model concern (`docs/05-data-model.md` Section 18).
9. **Failure behavior:** a failed or timed-out similarity computation is a
   retryable infrastructure/AI failure (Section 10); Java falls back to
   treating the item as non-duplicate for that attempt rather than guessing,
   consistent with never fabricating a result.
10. **Replaceability:** the embedding model or similarity mechanism can change
    independently of the deterministic threshold decision, and independently
    of every other capability (Section 13).

**Explicitly open:** the near-duplicate **threshold** (what score counts as a
near-duplicate) is a product/technical open question, not decided here
(`docs/02-functional-spec.md` Q12; `docs/03-technical-spec.md` Section 24,
T15; `docs/05-data-model.md` Section 18).

### 4.2 Classification

1. **Purpose:** determine which of the six predefined **areas of interest** a
   piece of information relates to, so relevance assessment has a scoped
   context to work against.
2. **Responsibility:** assign area(s) — an item may relate to more than one —
   and describe the type of information (e.g. an announcement, a filing, an
   analysis), per `docs/02-functional-spec.md` Section 7.5/8.2 and
   `docs/01-product-spec.md` Section 1.1.
3. **Does NOT:**
   - create a new area or extend the six approved areas
     (`docs/01-product-spec.md` Section 1.1; `docs/02-functional-spec.md`
     R20);
   - build a signal taxonomy, a "signal kind," or any classification hierarchy
     beyond area(s) and information type — none exists in the approved model
     (`docs/02-functional-spec.md` R21);
   - act as a recommendation system; it only labels content, it does not rank,
     score opportunities, or suggest actions
     (`docs/01-product-spec.md` Section 9).
4. **Inputs:** normalized item text, the fixed area-of-interest catalog, and
   the user's configured interests (`docs/03-technical-spec.md` Section 7.2,
   `classify`).
5. **Outputs:** the matched area(s), the information type, and a per-label
   rationale.
6. **Deterministic or AI-generated:** AI-generated (semantic judgement about
   subject matter) — `docs/03-technical-spec.md` Section 3.3.
7. **Validation expectations:** output area(s) must be drawn from the fixed
   six-area catalog only; structured-output schema validation applies as for
   every capability (Section 7).
8. **Source/provenance requirements:** classification does not itself need to
   cite sources (it labels, it does not assert new facts), but its output is
   always traceable to the specific item it classified.
9. **Failure behavior:** as Section 10 — a failed or invalid classification
   marks the item's processing as pending/failed for that stage; it does not
   silently default to "no area."
10. **Replaceability:** the model used for classification is independently
    configurable per `docs/03-technical-spec.md` Section 9.3; classification
    may later be combined with or separated from relevance assessment as an
    implementation choice (`docs/03-technical-spec.md` Section 7.2 note),
    without changing this capability's contract from the Java side.

**Implemented as (`docs/adr/0008-relevance-importance-signal-summary.md`):** the
option in item 10 was taken — there is **no separate `classify` capability**. The
six-area and interest matching is performed inside the `relevance` capability
(Section 4.3), which returns the matched area code(s) and interest id(s)
alongside its verdict; Java rejects any code or id it did not supply. A
standalone `classify` capability can still be reintroduced later without changing
the `relevance` contract from Java's side.

### 4.3 Relevance Assessment

1. **Purpose:** answer the first of the two questions the product flow
   requires: **is this information relevant to the user's configured
   interests?** (`docs/01-product-spec.md` core MVP flow;
   `docs/02-functional-spec.md` Section 7.6).
2. **Responsibility:** compare the classified item against the matched
   area(s)/interest(s) and produce a relevant/not-relevant judgement with a
   short, human-readable reason.
3. **Does NOT:**
   - decide importance — that is a separate capability (Section 4.4);
   - decide whether the item becomes a signal — that is a Java state
     transition (Section 6);
   - apply a numeric relevance score or confidence value as a fixed part of
     its contract — `docs/02-functional-spec.md` Section 7.5 only requires
     relevant/not-relevant plus a reason; whether a degree/confidence is also
     captured is not fixed (`docs/03-technical-spec.md` Section 7.2 lists an
     "optional degree" — its exact use is not further specified here).
4. **Inputs:** item text, the matched area(s), and the user's interests
   (`docs/03-technical-spec.md` Section 7.2, `assess-relevance`).
5. **Outputs:** relevant (boolean), a reason, the matched interest(s), and
   optionally a degree.
6. **Deterministic or AI-generated:** AI-generated
   (`docs/03-technical-spec.md` Section 3.3). Retaining the item as **Relevant
   Information** as a consequence of this output is a Java decision
   (`docs/05-data-model.md` Section 9).
7. **Validation expectations:** structured-output schema validation (Section
   7); the reason must be present whenever the item is marked relevant, so the
   user can see why (`docs/02-functional-spec.md` Section 9.2).
8. **Source/provenance requirements:** the assessment must be grounded in the
   supplied item text; it must not introduce claims the item does not contain
   (Section 8).
9. **Failure behavior:** as Section 10; an item whose relevance cannot be
   assessed is not silently treated as "not relevant" — it is marked
   pending/failed (`docs/02-functional-spec.md` W4 alternative flows).
10. **Replaceability:** model and prompt version are independently
    configurable (Section 9); no business code branches on which model
    produced the assessment.

**Explicitly open:** whether/how a relevance "degree" is used beyond the
boolean, and whether it is persisted, is not defined by any approved
specification (`docs/05-data-model.md` Section 9).

### 4.4 Importance Assessment

1. **Purpose:** answer the second question the product flow requires: **is
   this relevant information important enough to become a signal?**
   (`docs/01-product-spec.md` core MVP flow;
   `docs/02-functional-spec.md` Section 7.6).
2. **Responsibility:** given relevant information and its relevance context,
   produce an important-enough judgement with a reason and, where meaningful,
   an indication of uncertainty.
3. **Does NOT:**
   - define or apply a scoring formula, numeric threshold, ranking algorithm,
     or forecasting/opportunity-detection logic — none of these exist in the
     approved product model, and this document does not invent one
     (`docs/01-product-spec.md` Section 9; `docs/02-functional-spec.md`
     Section 7.6, R23);
   - create the Signal record, set its lifecycle state, or decide whether to
     alert — those are deterministic Java actions taken **as a consequence
     of** this assessment, never by this capability itself
     (`docs/03-technical-spec.md` Section 3.3; `docs/04-architecture.md`
     Section 8.2);
   - assess relevance — that is a separate, prior capability (Section 4.3).
4. **Inputs:** item text and its relevance context (matched areas/interests,
   relevance reason) (`docs/03-technical-spec.md` Section 7.2,
   `assess-importance`).
5. **Outputs:** important-enough (boolean), a reason, and an uncertainty
   indicator.
6. **Deterministic or AI-generated:** the **assessment** is AI-generated
   (semantic judgement); the **state transition** that turns "important
   enough = true" into a persisted Signal is deterministic Java logic
   (`docs/03-technical-spec.md` Section 3.3; `docs/04-architecture.md`
   Section 7.1).
7. **Validation expectations:** structured-output schema validation (Section
   7); a reason is required whenever the outcome is "important enough," so the
   user can see why the item became a signal
   (`docs/02-functional-spec.md` Section 9.2).
8. **Source/provenance requirements:** the assessment must be grounded in the
   supplied item text and relevance context; it must not fabricate a reason not
   supported by that content (Section 8).
9. **Failure behavior:** as Section 10; a failed importance assessment does not
   default to "important" or "not important" — it leaves the item
   pending/failed, consistent with never fabricating a result
   (`docs/02-functional-spec.md` R11-equivalent).
10. **Replaceability:** model/prompt independently configurable (Section 9); a
    future, more sophisticated importance capability could replace this one
    without changing the Java-side contract, as long as it returns the same
    shape of typed result.

**Explicitly open — this is the most important open point in this document:**
the **signal-selection criteria** — precisely how "important enough" is
judged, any guardrails Java might apply around the AI assessment before
transitioning state, and how novelty/magnitude are recognized — are **not**
defined here. This is a product decision, not an AI-design decision
(`docs/02-functional-spec.md` Q4; `docs/03-technical-spec.md` Section 24, T4
[signal-decision consumption], T16). This document defines **only** the shape
of the capability (an assessment with a reason), not the criteria behind it.

### 4.5 Summarization

1. **Purpose:** let the user understand a signal without opening the original,
   while the original remains reachable (`docs/02-functional-spec.md`
   Section 8.1).
2. **Responsibility:** produce a concise, source-grounded summary of a signal's
   underlying content.
3. **Does NOT:**
   - introduce facts the source content does not support
     (`docs/02-functional-spec.md` Section 8.2, R4);
   - frame its output as advice or a recommendation to act
     (`docs/02-functional-spec.md` R25);
   - decide the summary's final length/format policy — that is an open
     product question (Section 14), not something this capability fixes for
     itself;
   - replace or alter the original source content.
4. **Inputs:** item text and, where useful, source metadata
   (`docs/03-technical-spec.md` Section 7.2, `summarize`).
5. **Outputs:** a concise summary, with facts-vs-interpretation separation and
   grounding notes.
6. **Deterministic or AI-generated:** AI-generated
   (`docs/03-technical-spec.md` Section 3.3).
7. **Validation expectations:** structured-output schema validation; the
   summary is returned as structured fields (summary text, grounding notes,
   interpretation flags), not free text only (`docs/03-technical-spec.md`
   Section 9.4).
8. **Source/provenance requirements:** the summary is presented together with
   references to the source content it summarizes, and is never shown without
   that link (`docs/02-functional-spec.md` Section 8.2, R1;
   `docs/05-data-model.md` Section 15).
9. **Failure behavior:** if summarization fails or is unavailable, the signal
   is still shown with its other information and the summary is marked
   pending/failed rather than fabricated (`docs/02-functional-spec.md`
   Section 8.3, W5).
10. **Replaceability:** model/prompt independently configurable (Section 9);
    summarization can be retried or regenerated without affecting the
    underlying Signal or Relevant Information records.

**Explicitly open:** expected summary form/length
(`docs/02-functional-spec.md` Q14).

### 4.6 Embedding Generation

1. **Purpose:** produce a vector representation of content so it can be
   found by meaning (semantic search) and, where used, compared for near-
   duplicate detection (Section 4.1).
2. **Responsibility:** given text, return its embedding, the model id used,
   and the vector's dimension (`docs/03-technical-spec.md` Section 7.2,
   `embed`).
3. **Does NOT:**
   - persist the vector — persistence and retrieval belong to the Java
     backend via pgvector (`docs/03-technical-spec.md` Section 9.8, 11.2;
     `docs/04-architecture.md` Section 9);
   - choose which record(s) an embedding attaches to — that placement is
     deferred to `docs/07-rag.md` (`docs/05-data-model.md` Section 16);
   - perform retrieval or ranking — Java performs retrieval, this capability
     only produces vectors on request.
4. **Inputs:** text to embed.
5. **Outputs:** the vector, the model id, and the dimension.
6. **Deterministic or AI-generated:** the embedding model's output is
   AI/model-generated; given the same model and input it is expected to be
   reproducible, but it is not a hand-written deterministic rule
   (`docs/03-technical-spec.md` Section 7.2, "embedding model").
7. **Validation expectations:** the returned vector's dimension must match
   what the caller expects for the configured model; a mismatch is treated as
   an invalid result (Section 7).
8. **Source/provenance requirements:** not directly applicable — an embedding
   is a representation of supplied text, not a new assertion; the content it
   represents retains its own provenance in the data model
   (`docs/05-data-model.md` Section 16).
9. **Failure behavior:** as Section 10; a failed embedding call leaves the
   dependent operation (indexing, or a search/answer request) pending/failed
   rather than silently skipped.
10. **Replaceability:** the embedding model is configurable independently of
    every other capability (`docs/03-technical-spec.md` Section 9.3); changing
    it requires a controlled re-embedding step that this document does not
    design (`docs/05-data-model.md` Section 16).

**Explicitly open:** the embedding model, the vector dimension, and the
pgvector index type/parameters are **not** decided by this document
(`docs/03-technical-spec.md` Section 24, T3, T7). Task 8.3A ran a real local
benchmark of six Ollama embedding models and **provisionally recommends
`embeddinggemma` (768-dim)**, with `snowflake-arctic-embed2` as the fallback;
T3 stays formally open pending a re-run on real ingested content
(`docs/07-rag.md` Section 22; `docs/adr/0011-embedding-contract-and-local-model.md`).

### 4.7 Grounded Question Answering

1. **Purpose:** let the user ask a natural-language question and receive an
   answer built only from the knowledge base, with sources
   (`docs/02-functional-spec.md` Section 13.1).
2. **Responsibility:** given a question and the specific passages Java has
   already retrieved (via pgvector, with metadata filtering), synthesize an
   answer that cites only those passages, or state that the available
   information is insufficient.
3. **Does NOT:**
   - perform retrieval itself — retrieval (the pgvector query and metadata
     filtering) is a Java responsibility; this capability receives passages,
     it does not fetch them (`docs/03-technical-spec.md` Section 9.8;
     `docs/04-architecture.md` Section 6);
   - maintain conversation memory or act as a general-purpose assistant —
     Signal Engine is explicitly not a chatbot
     (`docs/02-functional-spec.md` Section 13.1, 13.2);
   - give financial, legal, or investment advice, recommend an action, or
     predict an outcome, even if asked — it reports what the supplied sources
     say and states that judgement belongs to the user
     (`docs/02-functional-spec.md` Section 13.2, R25).
4. **Inputs:** the question and the retrieved context passages, each with its
   source reference, as supplied by Java (`docs/03-technical-spec.md`
   Section 7.2, `answer`).
5. **Outputs:** a grounded answer with per-claim citations to the supplied
   passages, **or** an explicit "insufficient information" result.
6. **Deterministic or AI-generated:** AI-generated (answer synthesis);
   retrieval itself, which supplies the passages, is a deterministic Java
   operation (`docs/03-technical-spec.md` Section 3.3, 9.8).
7. **Validation expectations:** structured-output schema validation; every
   citation in the answer must reference a passage that was actually supplied
   — the capability must not cite a source it was not given.
8. **Source/provenance requirements:** the answer must be grounded strictly in
   the supplied passages; when those passages do not support an answer, the
   result must say so rather than inventing one
   (`docs/02-functional-spec.md` R2; Section 8 below).
9. **Failure behavior:** if the AI/provider is unavailable, the user is told
   answering is temporarily unavailable — no fabricated answer is ever
   returned (`docs/02-functional-spec.md` Section 13.3, W11).
10. **Replaceability:** model/prompt independently configurable (Section 9);
    the retrieval strategy that supplies passages may evolve independently
    (`docs/07-rag.md`) without changing this capability's contract.

**Explicitly open:** whether multi-turn conversational context is ever
supported (`docs/02-functional-spec.md` Q21) — not decided here; the current
design assumes each question is independent.

### 4.8 Semantic Chunk Boundary Detection

Added by Task 8.2 (`docs/adr/0010-indexing-foundation-and-semantic-chunking.md`),
which was not anticipated when Sections 4.1–4.7 were written. It is an indexing-
side capability, used only when content is prepared for retrieval.

1. **Purpose:** decide, from meaning alone, where a document divides into
   coherent, self-contained sections, so it can be chunked along semantic
   boundaries rather than by arbitrary size (`docs/07-rag.md` Section 21).
2. **Responsibility:** given a boundary prompt composed by the
   `SlaouiSS/semantic-chunker` library — a numbered sequence of document units
   and a fixed instruction — return the identifiers of the units at which a new
   section begins, as an ascending integer list (`[N1,N2,…]`) or `[]`.
3. **Does NOT:** parse or validate its own answer (the library does that and
   applies its own bounded retry); chunk, embed, persist, or retrieve anything;
   read any database or the open web.
4. **Inputs:** the library-composed prompt and a temperature hint.
5. **Outputs:** the model's raw answer text, delivered back to the library
   unchanged.
6. **Deterministic or AI-generated:** AI-generated (a boundary judgement);
   temperature is 0 for consistency.
7. **Validation expectations:** performed by the library, not the capability —
   the answer must be a list of in-range unit identifiers, ascending, excluding
   the first unit.
8. **Source/provenance requirements:** unit text is untrusted source data; the
   prompt instructs the model to analyse it and never follow instructions inside
   it (Section 8).
9. **Failure behavior:** as Section 10; a provider failure is a typed
   `AI_PROVIDER_*` error and the library treats a failure to obtain a response
   as terminal for that document.
10. **Replaceability:** model and provider are configuration only
    (`AGENTS_LLM_PROVIDER` — NVIDIA Build is the current/default generative
    provider, the final provider decision (`docs/adr/0006-ai-java-python-foundation.md`);
    Ollama remains a selectable fallback for the chat provider); the
    capability contract does not change. It uses the existing chat provider port,
    so Section 9's "two ports are sufficient" still holds.

**Explicitly open:** the chunking parameters proper (passage size, overlap, unit
granularity) and the model to use are not decided here (`docs/07-rag.md`
Section 21).

---

## 5. Capability Inputs and Outputs

Summary view (details in Section 4; exact field-level schemas belong to
implementation work, not this document):

| Capability | Primary input | Primary output | AI or deterministic |
|---|---|---|---|
| Near-Duplicate Assessment | candidate + comparison text/vectors | similarity score(s) | Similarity: AI-adjacent/deterministic math; the ensuing decision: deterministic |
| Classification | item text, area catalog, interests | area(s), information type, rationale | AI |
| Relevance Assessment | item text, matched area(s), interests | relevant (bool), reason, matched interests | AI |
| Importance Assessment | item text, relevance context | important-enough (bool), reason, uncertainty | AI (assessment); Java (transition) |
| Summarization | item text, source metadata | summary, grounding notes, interpretation flags | AI |
| Embedding Generation | text | vector, model id, dimension | AI/model-generated |
| Grounded Q&A | question, retrieved passages (from Java) | grounded answer + citations, or "insufficient information" | AI (synthesis); Java (retrieval) |

Every request and response also carries the common envelope already defined in
`docs/03-technical-spec.md` Section 8.2: a correlation id, a contract version,
model/provider metadata, timing, and — for semantic outputs — a
facts-vs-interpretation distinction and, where meaningful, an
uncertainty/confidence indicator. This document does not redefine or extend
that envelope.

---

## 6. Agent Boundaries and Responsibilities

The following boundaries apply to **every** capability in Section 4, without
exception:

- **One focused capability, one responsibility.** No capability performs more
  than the single judgement or transformation described in its Section 4
  entry. There is no "do everything" agent (`CLAUDE.md` Section 12;
  `docs/03-technical-spec.md` Section 7.1).
- **Agents do not orchestrate other agents.** Java sequences capability calls
  (e.g. classify → assess relevance → assess importance → summarize); no
  capability calls another capability directly.
- **Agents do not schedule work.** Collection cadence, retry sweeps, and
  timing are entirely Java's responsibility (`docs/03-technical-spec.md`
  Section 6.3, 6.5).
- **Agents do not manage business workflows.** A capability answers the one
  request it received; it has no notion of "what happens next" in the
  pipeline.
- **Agents do not write to PostgreSQL.** No capability has a database
  connection; persistence is exclusively a Java responsibility
  (`docs/03-technical-spec.md` Section 7.4; `docs/04-architecture.md`
  Section 9).
- **Agents do not decide final business-state transitions.** Creating a
  Signal, changing its lifecycle state, recording Feedback, or raising an
  Alert are Java decisions that may be *informed by* a capability's output but
  are never *performed by* it (`docs/04-architecture.md` Section 8.2, 17).
- **Agents receive explicit inputs and return explicit structured outputs.**
  No capability infers context it was not given, and no capability returns
  unstructured free text where its result is consumed programmatically
  (Section 7).
- **Java decides how and when capabilities are invoked**, including which
  model/provider configuration applies to a given call
  (`docs/03-technical-spec.md` Section 9.3).
- **Java validates business-level state transitions.** A capability's typed
  result becomes business fact only after Java has validated the response
  against its contract schema (`docs/03-technical-spec.md` Section 8.3) and
  applied whatever deterministic rule governs that transition.
- **AI output is advisory to the deterministic application workflow, not
  authoritative business state.** This is the single most important boundary
  in this document and applies uniformly to all seven capabilities above.

---

## 7. Structured Output and Validation

- **Structured output is required wherever a result is consumed
  programmatically.** Classification, relevance assessment, importance
  assessment, summarization (as structured fields), embedding generation, and
  Q&A citations all request an explicit schema rather than uncontrolled free
  text (`docs/03-technical-spec.md` Section 9.4).
- **Schema validation happens on both sides of the contract:** the Python
  capability validates the model's structured output against the capability's
  result schema before returning it; the Java backend independently validates
  every Python response against the same contract schema before the result
  enters business logic (`docs/03-technical-spec.md` Section 8.3).
- **Outputs are bounded.** A capability call is expected to produce one
  well-formed result (or a typed error) within its configured timeout — not an
  open-ended or unbounded generation (`docs/03-technical-spec.md` Section 9.7,
  13.4).
- **One bounded repair attempt is already defined:** on a schema-invalid
  model output, the capability makes one re-prompt attempt using the
  validation error, then, on repeated failure, returns a typed
  `AI_OUTPUT_INVALID` error rather than looping or fabricating a result
  (`docs/03-technical-spec.md` Section 9.6). This document does not change
  that count or introduce a second repair mechanism.
- **Failure to obtain a valid structured result is a defined outcome, not an
  exception to work around.** The affected item/request is marked
  pending/failed and surfaced through Java's normal failure handling
  (Section 10; `docs/02-functional-spec.md` Section 15.2).

No exact schema (field names, types) is defined in this document beyond what
`docs/03-technical-spec.md` Section 7.2 and Section 8.2 already fix (the
capability table and the common envelope); precise schemas are implementation
work.

---

## 8. Source Grounding and Hallucination Prevention

Applies to relevance, importance, classification, summarization, and question
answering — every capability whose output could be shown to, or acted on by,
the user:

- **AI operates only on supplied source content/context.** No capability is
  given open-ended access to the internet, to other sources, or to
  information beyond what Java explicitly passes in the request
  (`docs/01-product-spec.md` Section 3).
- **AI must not silently invent facts.** Facts drawn from the source must
  remain distinguishable from any interpretation the model adds
  (`docs/02-functional-spec.md` R4).
- **Summaries remain grounded in the supplied source material.** A summary
  must not assert something the source content does not support
  (`docs/02-functional-spec.md` Section 8.2).
- **Question answering is based on retrieved passages supplied by Java**,
  never on the model's own general knowledge; citations reference only those
  supplied passages (`docs/03-technical-spec.md` Section 9.8).
- **Insufficient evidence produces an explicit limitation, not a
  fabrication.** If the supplied content or retrieved passages do not support
  a confident answer or summary, the result must say so — "insufficient
  information" for Q&A, an explicit uncertainty indicator for importance
  assessment — rather than filling the gap with an invented claim
  (`docs/02-functional-spec.md` Section 13.2, W11; Section 4.4, 4.7 above).
- **No user-facing AI output is framed as advice.** This applies across every
  capability that produces user-visible text (`docs/02-functional-spec.md`
  R25).

Architectural controls (structured output, schema validation, and this
grounding discipline) are the primary safeguard — not prompt wording alone
(`CLAUDE.md` Section 16; `docs/03-technical-spec.md` Section 9.5).

---

## 9. AI Provider Abstraction

Every capability reaches a model through the project's own provider
abstraction, never directly:

```
Application capability (Java use case, or Python capability handler)
            │  depends on a port / interface only
            ▼
AI capability service
            │
            ▼
LlmChatProvider  /  EmbeddingProvider    (project-owned ports)
            │
            ▼
Provider adapter (e.g. an Ollama adapter; future: other adapters)
            │
            ▼
Model runtime (Ollama / future providers)
```

(`docs/03-technical-spec.md` Section 9.1–9.2; `docs/04-architecture.md`
Section 9.)

- **`LlmChatProvider`** covers chat/structured-output generation, used by
  classification, relevance assessment, importance assessment, summarization,
  and question answering.
- **`EmbeddingProvider`** covers embedding generation, used by embedding
  generation and, where applicable, near-duplicate assessment.
- **No additional provider abstraction is introduced.** These two ports are
  sufficient for every capability in Section 4; this document does not add a
  third.
- **On the Java side**, the adapter implementation is Spring AI, fully
  contained in infrastructure and never exposed to the application or domain
  layers (`docs/03-technical-spec.md` Section 4.7, 9.2).
- **On the Python side**, the same conceptual port is a plain Python object
  behind a lightweight `typing.Protocol`, composed natively — not a Spring AI
  binding and not resolved through a container
  (`docs/03-technical-spec.md` Section 7.1, 9.2).
- **No capability, prompt, or business rule names `ollama` or `gpt-oss:20b`**
  outside configuration and the adapter itself
  (`docs/03-technical-spec.md` Section 3.6, 9.1).
- **Different capabilities may use different models**, purely through
  configuration, with no change to any capability's contract
  (`docs/03-technical-spec.md` Section 9.3).

---

## 10. Failure and Recovery Boundaries

Conceptual failure categories relevant to AI capabilities, consistent with
`docs/03-technical-spec.md` Section 13 (not redefined here, only related to
the capabilities of Section 4):

| Failure | Conceptual meaning | Where it surfaces |
|---|---|---|
| Invalid AI output | The model's structured output fails schema validation, even after the one bounded repair attempt | Capability returns a typed `AI_OUTPUT_INVALID` error; Java marks the affected item/request pending or failed |
| Insufficient source context | The supplied content/passages do not support a confident result | Not a hard failure — an explicit, defined outcome (Section 8): "insufficient information," an uncertainty flag, or a "not relevant" judgement with a reason |
| Provider/model failure | The LLM/embedding runtime is unavailable or errors | Treated as an infrastructure/AI-provider failure at the provider-adapter boundary; retryable per the transport-failure category |
| Timeout | A call exceeds its configured timeout | Retryable, bounded, per the timeout/backoff policy already defined in `docs/03-technical-spec.md` Section 13.3–13.4 |
| Inability to produce a valid result after retries | Repair attempt and/or retries exhausted | The item/request is left in a failed/pending state, visible in activity, never silently dropped and never fabricated (`docs/02-functional-spec.md` R11) |

This document does not redefine retry counts, backoff parameters, or timeout
values — those remain exactly as fixed (as proposed defaults, open to tuning)
in `docs/03-technical-spec.md` Section 13.3–13.4. It only maps the failure
categories onto the AI capabilities described here.

---

## 11. Interaction with Java Orchestration

The Java backend is the only component that sequences capability calls. A
typical item's path through the capabilities (mirroring
`docs/03-technical-spec.md` Section 6.3, 7.2 and `docs/04-architecture.md`
Section 7):

```
Java: normalize → dedup (deterministic)
   │
   ├─► Python: Near-Duplicate Assessment (if not an exact duplicate)
   │        Java: apply threshold → duplicate | continue
   │
   ├─► Python: Classification
   │        Java: record area(s)/type
   │
   ├─► Python: Relevance Assessment
   │        Java: retain as Relevant Information, or set aside as noise
   │
   ├─► Python: Importance Assessment   (only if relevant)
   │        Java: create Signal + set state, or leave as Relevant
   │              Information only
   │
   ├─► Python: Summarization           (only if a Signal was created)
   │        Java: attach summary to the Signal
   │
   └─► Java: raise alert if warranted, persist, done

Separately, on demand:
   Java: retrieval (pgvector) → Python: Grounded Question Answering
   Java or the collection pipeline → Python: Embedding Generation
```

At every arrow into Python, Java supplies the explicit input the capability
needs; at every arrow back, Java validates the response before acting on it.
No capability call causes another capability call — Python never calls Python,
and Python never calls Java.

---

## 12. AI Processing Flow

Restating Section 3's flow with the capability responsible at each AI step
made explicit:

```
Reliable sources
      ↓
  Collection              — deterministic (Java; per-source-type connector)
      ↓
 Normalization            — deterministic (Java)
      ↓
 Deduplication            — deterministic identity/hash match (Java);
      │                     semantic near-duplicate check (Section 4.1)
      ↓
 Classification           — Section 4.2 (AI)
      ↓
   Relevance               — Section 4.3 (AI); retain decision (Java)
      ↓
Importance / signal        — Section 4.4 (AI assessment);
   decision                  state transition (Java, deterministic)
      ↓
   Summary                 — Section 4.5 (AI)
      ↓
Signal + source link       — deterministic (Java; provenance preserved)
      ↓
Search / Question          — Embedding Generation (Section 4.6) at index
   Answering                 time; retrieval (Java, deterministic);
                              Grounded Q&A synthesis (Section 4.7, AI)
      ↓
  Simple alert              — deterministic (Java)
```

No AI step is added to this flow beyond what `docs/03-technical-spec.md`
Section 6.2 and Section 10.1 already define.

---

## 13. Replaceability and Evolution

Consistent with `docs/03-technical-spec.md` Section 21 and
`docs/04-architecture.md` Section 11, and restated here specifically for AI
capabilities:

- **A capability's implementation can be replaced** without affecting the Java
  side, as long as it continues to satisfy the capability's contract (its
  request/response shape).
- **A provider or model can be swapped** by implementing/configuring a new
  `LlmChatProvider` or `EmbeddingProvider` adapter — no change to any
  capability's logic or to Java business code
  (`docs/03-technical-spec.md` Section 21.3–21.4).
- **A capability can be added, replaced, or removed** by defining/retiring its
  versioned contract and its handler, with no God-agent risk because each
  capability already keeps one responsibility
  (`docs/03-technical-spec.md` Section 21.5).
- **The single Python AI service may later be split** into several
  deployables (e.g. by model size or hardware need), routed per capability by
  configuration, with contracts unchanged. This is a future option, not part
  of the current design, and is not designed further here
  (`docs/03-technical-spec.md` Section 21.6, Section 24 T12).
- **Capabilities are designed to be evaluable later**, in the sense that every
  response already carries model/provider metadata, a prompt version, and a
  facts-vs-interpretation distinction (`docs/03-technical-spec.md`
  Section 8.2, 18.3, "structured to add it"). This document does **not**
  define an evaluation subsystem, an evaluation pipeline, or evaluation
  criteria — that remains explicitly deferred
  (`docs/01-product-spec.md` Section 8; `docs/03-technical-spec.md`
  Section 16.3).

---

## 14. Open Questions

This document resolves none of the following; each is carried forward from
the specification that originated it.

- **Signal-selection criteria** — how "important enough" is judged, and any
  guardrails Java applies around the importance assessment before creating a
  Signal (`docs/02-functional-spec.md` Q4; `docs/03-technical-spec.md`
  Section 24, T16).
- **Near-duplicate threshold** — the numeric cutoff for the similarity score
  used in Section 4.1 (`docs/02-functional-spec.md` Q12;
  `docs/03-technical-spec.md` Section 24, T15).
- **Embedding model and vector dimension** — not chosen (Section 4.6;
  `docs/03-technical-spec.md` Section 24, T3).
- **pgvector index type and parameters** — not chosen
  (`docs/03-technical-spec.md` Section 24, T7).
- **Multilingual behavior** — the MVP processes English-language content only;
  future language support, and how classification/relevance/summarization
  would behave for other languages, is not designed here
  (`docs/02-functional-spec.md` Q24; `docs/03-technical-spec.md` Section 24,
  T19).
- **Exact output schemas** beyond the capability table and common envelope
  already fixed in `docs/03-technical-spec.md` Section 7.2 and 8.2 — precise
  field names/types are implementation work, not defined here.
- **Exact model assignment per capability** — beyond "the generative LLM
  defaults to NVIDIA Build, embeddings default to Ollama running
  `embeddinggemma`, both configurable per capability"
  (`docs/03-technical-spec.md` Section 9.3), which capability might later use
  a different model is not decided.
- **Exact prompt design** — prompt wording and structure are implementation
  work, versioned as described in `docs/03-technical-spec.md` Section 9.5, not
  defined in this document.
- **Future splitting of the Python service** — the mechanism exists
  (Section 13); whether/when to use it is not decided
  (`docs/03-technical-spec.md` Section 24, T12).
- **Whether/how a relevance "degree" is used** beyond the relevant/not-relevant
  boolean (Section 4.3).
- **Whether question answering ever supports multi-turn context**
  (`docs/02-functional-spec.md` Q21).

No new question identifier is created; every open point above uses an
existing identifier from `docs/02-functional-spec.md` or
`docs/03-technical-spec.md`.

---

## 15. Summary

Signal Engine's AI architecture is a small set of **focused, stateless
capabilities** — near-duplicate assessment, classification, relevance
assessment, importance assessment, summarization, embedding generation, and
grounded question answering — each with one responsibility, each reached from
Java through explicit, versioned, schema-validated contracts, and each
implemented behind the project's own `LlmChatProvider`/`EmbeddingProvider`
abstractions rather than any specific provider or model.

Every capability's output is **advisory**: it informs a deterministic decision
that only the Java backend makes and only the Java backend persists. AI never
writes business state, never orchestrates other capabilities, never schedules
work, and never accesses PostgreSQL directly. Every capability that produces
user-facing or business-relevant content operates strictly on supplied source
content, distinguishes fact from interpretation, and reports insufficient
evidence honestly rather than fabricating an answer.

Nothing in this document introduces a new product capability, a signal
taxonomy, a scoring formula, an authority model, a new provider abstraction, or
an evaluation subsystem. Every open point — the signal-selection criteria, the
near-duplicate threshold, the embedding model and vector dimension, the
pgvector index strategy, multilingual behavior, exact schemas, exact model
assignment, exact prompt design, and future Python service splitting —
**remains explicitly open**, to be resolved by the product, functional, or
technical decision it belongs to, not by this document.
