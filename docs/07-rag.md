# Signal Engine — Retrieval-Augmented Generation (RAG)

Document ID: `07-rag.md`
Status: Draft — awaiting review
Depends on: `CLAUDE.md`, `docs/01-product-spec.md`, `docs/02-functional-spec.md`,
`docs/03-technical-spec.md`, `docs/04-architecture.md`, `docs/05-data-model.md`,
`docs/06-ai-agents.md`

---

### Preamble — scope of this document

This document clarifies **how** Signal Engine stores content representations,
generates embeddings, retrieves relevant information, and supplies grounded
context to the Question Answering capability defined in
`docs/06-ai-agents.md` Section 4.7. It exists because
`docs/03-technical-spec.md` Section 1.3 explicitly defers "retrieval strategy,
chunking, context construction" to this document, and because
`docs/03-technical-spec.md` Section 11.2 and `docs/05-data-model.md` Section 16
both explicitly defer the exact placement of embeddings to this document.

Signal Engine is **not** a general-purpose chatbot and **not** a sophisticated
enterprise RAG platform (`docs/02-functional-spec.md` Section 13.1). This
document keeps the RAG design as simple as the two capabilities it exists to
support: semantic search and grounded question answering
(`docs/01-product-spec.md` Section 6; `docs/02-functional-spec.md`
Sections 12–13).

This document does **not**:

- write SQL, migrations, table/column names, or index definitions;
- write Python modules, Java classes, API endpoints, or prompts;
- choose an embedding model, vector dimension, chunking strategy, or index
  type;
- redefine the data model of `docs/05-data-model.md` or the AI capabilities of
  `docs/06-ai-agents.md` — it applies them to the retrieval problem;
- resolve any open product, functional, or technical question. Open points are
  named and preserved (Section 18).

**Update (Phase 2, Task 8.1).** Section 20 was added when the reusable RAG core
contracts were implemented (`docs/adr/0009-rag-core-architecture-and-contracts.md`).
That work is contracts and composition only — it introduces Java interfaces and
value types for the pipeline stages, but still chooses no embedding model, vector
dimension, chunking strategy, index type, retrieval parameter, reranking
implementation, context budget, or evaluation metric. Every open point in
Section 18 remains open.

---

## 1. RAG Purpose

RAG exists to support exactly two already-approved MVP capabilities. No third
purpose is introduced.

### Semantic Search

The user searches the Signal Engine knowledge base using natural language and
receives relevant stored information/signals, ranked by semantic similarity
and narrowed by basic metadata filters (`docs/02-functional-spec.md`
Section 12).

### Grounded Question Answering

The user asks a question. Java retrieves relevant passages from
PostgreSQL/pgvector. Python receives those passages and synthesizes an answer.
The answer cites the supplied source context, or states that the available
information is insufficient (`docs/02-functional-spec.md` Section 13;
`docs/06-ai-agents.md` Section 4.7).

**The AI must never independently retrieve information from the Internet.**
Retrieval is exclusively a Java operation over Signal Engine's own stored
knowledge; no capability performs open-web search
(`docs/03-technical-spec.md` Section 9.8; `docs/06-ai-agents.md` Section 4.7,
item 3).

---

## 2. RAG Architecture

```text
Stored Signal Engine knowledge
        │
        ▼
Textual content / passages           (Section 3, 4)
        │
        ▼
Embedding Generation                 (Python capability — docs/06 §4.6)
        │
        ▼
PostgreSQL + pgvector                (Java-owned storage — Section 6)
        │
        │ semantic retrieval
        ▼
Java retrieval logic                 (similarity + metadata filtering)
        │
        ├──────────────► Semantic Search result   (Section 9)
        │
        ▼
Retrieved passages + provenance
        │
        ▼
Python Grounded Q&A capability       (docs/06 §4.7)
        │
        ▼
Grounded answer + citations
```

**Ownership is explicit and unchanged from the prior documents:**

- **Python generates embeddings** (`docs/06-ai-agents.md` Section 4.6).
- **Java persists embeddings** in PostgreSQL/pgvector
  (`docs/03-technical-spec.md` Section 9.8, 11.2).
- **Java performs retrieval** — the similarity query and metadata filtering
  (`docs/03-technical-spec.md` Section 9.8, 11.3).
- **Python performs answer synthesis** from the passages Java supplies
  (`docs/06-ai-agents.md` Section 4.7).
- **Java remains responsible for the business/API workflow** — search and
  question-answering are API-level use cases owned by the Java backend
  (`docs/03-technical-spec.md` Section 6.6, 12.1).

---

## 3. Content Representation

RAG operates on the concepts already defined in `docs/05-data-model.md`; it
does not add a new business entity. In particular, this document does **not**
introduce a business concept called "Document" — where a technical
representation is needed for retrieval, it is described below as exactly
that: a **technical representation**, not a product concept.

The conceptual chain from source to retrievable unit:

```
Source
  ↓
Raw Information Item        (collected, as-fetched content — docs/05 §8)
  ↓
Relevant Information        (retained because assessed relevant — docs/05 §9)
  ↓
Signal                      (retained because assessed important — docs/05 §10)
  ↓
Summary / source content    (the text available to describe the signal —
  ↓                          docs/05 §11)
Retrieval passage(s)        (a technical, bounded unit of text prepared
  ↓                          for embedding and retrieval — Section 4)
Embedding                   (a vector representation of a passage —
                             Section 5)
```

**Textual retrieval units ("passages") are a technical necessity, not a new
business concept.** Signal Engine's knowledge base is made of Raw Information
Items, Relevant Information, Signals, and Summaries; a "passage" is simply
however much of that text is prepared for embedding and retrieval at a given
point. It carries no meaning beyond that.

**Not every entity necessarily gets its own embedding.** Whether a Relevant
Information record, a Signal, its Summary, or some chunk thereof is the unit
that gets embedded is an implementation/design question this document does
not close (Section 4, Section 18). What is fixed conceptually:

- **Every retrievable passage needs a vector representation** to participate
  in semantic search or retrieval for Q&A.
- **Every retrieved passage must retain enough provenance/context to identify
  its original source** — at minimum, a path back to the Raw Information
  Item(s) and Source it came from (Section 11).
- **The retrieval representation must not replace the underlying business
  data.** A passage and its embedding are a derived, technical view for
  retrieval; the Relevant Information/Signal/Summary records in
  `docs/05-data-model.md` remain the business source of truth for that
  content.

---

## 4. Chunking / Passages

Whole collected articles or long summaries can be too large, or too
heterogeneous, to serve as a single precise retrieval unit; smaller passages
can improve retrieval precision; and grounded Q&A needs a **bounded** amount of
context to work from (`docs/03-technical-spec.md` Section 9.7, 13.4 — bounded
calls generally). This is why the concept of a "passage" (Section 3) exists at
all.

**This document does not define:**

- exact chunk size,
- exact token count,
- overlap percentage,
- tokenizer,
- chunking algorithm (fixed-size, recursive splitter, heading-based, or
  otherwise),
- semantic chunking model.

These are implementation/design decisions, not fixed by any approved
specification, and are preserved as open (Section 18).

**Three things must remain clearly distinct:**

- **Source content** — the original text collected from a source (Raw
  Information Item content, `docs/05-data-model.md` Section 8).
- **A retrieval passage** — a technical, bounded unit of text derived from
  source content (or from a Summary) and prepared for embedding.
- **A generated Summary** — the AI-produced, concise, source-grounded account
  of a Signal (`docs/06-ai-agents.md` Section 4.5).

**A summary is not automatically the retrieval passage, and summaries do not
automatically replace source content for retrieval.** Whether search and
retrieval draw on raw/normalized source content, on the Summary, on a
purpose-built passage derived from either, or on some combination, is an open
implementation question (Section 18) — this document only fixes that these are
conceptually different things and must not be silently conflated.

---

## 5. Embedding Generation

This section aligns strictly with `docs/06-ai-agents.md` Section 4.6 and adds
nothing to it.

- **Receives** text.
- **Returns** a vector, the model id used, and the vector's dimension.
- **Does not persist** the vector — persistence is a Java responsibility
  (Section 6).
- **Does not retrieve** — Embedding Generation only produces vectors on
  request; it never queries pgvector.
- **Does not rank** — ranking/retrieval logic lives in Java (Section 7).

**Python generates embeddings; Java persists them in PostgreSQL/pgvector.**
The capability is reached only through the project-owned
**`EmbeddingProvider`** abstraction, never directly against a specific
provider (`docs/03-technical-spec.md` Section 9.1–9.2; `docs/06-ai-agents.md`
Section 9).

**Not decided here, and not decided by any prior document:** the embedding
model and the vector dimension. This remains **open question T3**
(`docs/03-technical-spec.md` Section 24; `docs/06-ai-agents.md` Section 14).

---

## 6. Vector Storage

**PostgreSQL remains the single source of truth; pgvector provides vector
storage and similarity search inside that same database** — not a second
datastore (`docs/03-technical-spec.md` Section 4.3, 11.1–11.2;
`docs/04-architecture.md` Section 9; `docs/05-data-model.md` Section 16).

This document does not design SQL, migrations, table names, or columns. It
does not decide:

- where a vector column is placed (which record(s) an embedding attaches to —
  Section 3's open point),
- the exact table mapping,
- the index type (e.g. HNSW vs. IVFFlat),
- index parameters, or
- the distance metric, where that remains open.

**Open question T7 — pgvector index type and parameters** — is explicitly
preserved (`docs/03-technical-spec.md` Section 24; `docs/06-ai-agents.md`
Section 14).

**Update (Task 8.3B).** Section 23 records the concrete schema
(`rag_passage`, `rag_passage_embedding`, migration V11), the vector column
(`vector(768)`), and the distance metric (cosine). T7 is **still open**: no
approximate-nearest-neighbour index is created &mdash; the column is ready for
one when the index type and parameters are decided
(`docs/adr/0012-pgvector-persistence-and-indexing.md`).

**The vector layer is infrastructure for retrieval, not a second source of
truth.** A vector exists to help Java find relevant content quickly; the
business meaning and provenance of that content live in the records described
in `docs/05-data-model.md`, not in the vector itself (Section 11).

---

## 7. Retrieval

```text
User query
    ↓
Java prepares query
    ↓
Embedding Generation           (Python — Section 5)
    ↓
query vector
    ↓
PostgreSQL / pgvector          (Section 6)
    ↓
similarity retrieval
    ↓
basic metadata filtering       (Section 8)
    ↓
retrieved passages
```

**Ownership, restated explicitly:**

- **Java performs retrieval.** The similarity query and metadata filtering run
  in the Java backend, using pgvector, as already established
  (`docs/03-technical-spec.md` Section 9.8, 11.3; `docs/04-architecture.md`
  Section 9).
- **Python does not query pgvector and does not access PostgreSQL** in any
  capability, including Grounded Q&A (`docs/03-technical-spec.md` Section 7.4;
  `docs/06-ai-agents.md` Sections 2, 4.7).
- **The Grounded Q&A capability only receives passages Java has already
  selected** — it never fetches its own context (`docs/06-ai-agents.md`
  Section 4.7, item 3).

**No reranking and no sophisticated ranking are introduced.** The MVP uses
semantic similarity plus the basic metadata filtering already approved
(`docs/01-product-spec.md` Section 8; `docs/02-functional-spec.md` R19;
`docs/03-technical-spec.md` Section 11.3, 25).

**Not decided here:** exact retrieval parameters (e.g. how many passages are
retrieved per query, any similarity cutoff beyond "semantic similarity") are
implementation-level design points not fixed by any approved specification,
and are preserved as open (Section 18).

**Implemented by Task 8.3C** (Section 24, `docs/adr/0013-...`): the concrete
`Query → EmbeddingModel → exact pgvector cosine scan → Top-K` retriever, with a
provisional configurable `topK` default of 5.

---

## 8. Metadata Filtering

Basic metadata filtering narrows retrieval alongside semantic similarity,
using metadata concepts already established in `docs/02-functional-spec.md`
Section 12 and `docs/05-data-model.md`:

- **area of interest**
- **interest**
- **source**
- **time/date**

Additional metadata already recognized elsewhere in the model (e.g. state,
where applicable) may also apply, consistent with
`docs/02-functional-spec.md` Section 12.2.

**No large filtering language or advanced query DSL is introduced.**
Filtering is a small, fixed set of metadata constraints applied alongside
semantic similarity — not a general-purpose query language.

**This is not personalization.** Metadata filtering narrows *what the user
explicitly asked for* (a filter they applied, or the scope implied by their
configured interests); it is not a ranking or recommendation mechanism, and it
does not introduce the complex personalization explicitly out of scope
(`docs/01-product-spec.md` Section 9; `docs/02-functional-spec.md`
Section 2.2).

---

## 9. Semantic Search

```text
User query
    ↓
semantic retrieval             (Section 7)
    ↓
relevant stored information/signals
    ↓
source/provenance              (Section 11)
```

Every search result remains connected to its original source, exactly as
required by `docs/02-functional-spec.md` Section 12.2: each result shows
enough context to be understood (title, summary, area(s), source(s),
timestamps) and links to the original source(s).

**No sophisticated ranking formula and no recommendation behavior are
defined.** Search helps the user find information Signal Engine has already
collected — it does not perform open-web search, and it does not rank results
by anything beyond semantic similarity plus the basic metadata filters of
Section 8 (`docs/02-functional-spec.md` Section 12.1; `docs/03-technical-spec.md`
Section 11.3, R19).

---

## 10. Grounded Question Answering

This section aligns strictly with `docs/06-ai-agents.md` Section 4.7 and adds
nothing to it.

```text
User question
      ↓
Java retrieval                 (Section 7)
      ↓
Retrieved passages
      ↓
Python Grounded Q&A
      ↓
Grounded answer + citations
```

**Python must:**

- receive the question;
- receive the retrieved passages;
- receive provenance/source references for those passages;
- synthesize an answer from that supplied material only;
- cite only the supplied passages;
- state that available information is insufficient when the evidence does not
  support a confident answer.

**Python must NOT:**

- retrieve — retrieval is exclusively Java's responsibility (Section 7);
- search the web;
- access PostgreSQL;
- invent citations — every citation must reference a passage actually
  supplied in the request;
- use hidden external knowledge as evidence;
- maintain general assistant memory — Signal Engine is explicitly not a
  chatbot (`docs/02-functional-spec.md` Section 13.1–13.2);
- provide financial, legal, or investment advice, even if asked
  (`docs/02-functional-spec.md` R25).

The answer is grounded strictly in the supplied retrieval context — never in
the model's own general knowledge (`docs/03-technical-spec.md` Section 9.8;
`docs/06-ai-agents.md` Section 4.7, Section 8).

---

## 11. Provenance

Provenance is mandatory throughout the RAG pipeline, exactly as it is
throughout the rest of the system (`docs/01-product-spec.md` Section 3;
`docs/05-data-model.md` Section 15).

```
Source
  ↓
Raw Information Item
  ↓
Relevant Information
  ↓
Signal
  ↓
Summary / retrieval passage
  ↓
Embedding / retrieval reference
  ↓
Answer citation
```

The **exact physical representation** of this chain (which table or column
carries which reference) is the responsibility of the data model and future
implementation work, not of this document (`docs/05-data-model.md`
Sections 15, 24).

**An embedding does not become the source of truth.** A vector is a technical
aid for finding content by meaning; it is not, and cannot be, a substitute
for:

- the source URL,
- source identity,
- the underlying content itself, or
- the recorded provenance chain above.

**Every retrieved passage used for Q&A must be traceable back to the original
source**, and every citation in a grounded answer must resolve to that
traceable source (`docs/02-functional-spec.md` R1, R2; `docs/06-ai-agents.md`
Section 4.7, item 7–8).

---

## 12. RAG and the Signal Engine Data Model

This section explains how RAG relates to `docs/05-data-model.md` **without
redefining it**.

| Data-model concept | Role with respect to RAG |
|---|---|
| **Raw Information Item** | Collected source content; a possible source of text for retrieval passages (`docs/05-data-model.md` Section 8) |
| **Relevant Information** | Content judged relevant to the user's interests; part of the knowledge base that search and Q&A operate over (`docs/05-data-model.md` Section 9) |
| **Signal** | Information judged important enough to surface; the primary unit the user searches for and asks about (`docs/05-data-model.md` Section 10) |
| **Summary** | Generated, source-grounded presentation content; a candidate source of text for a retrieval passage, but not automatically identical to one (Section 4) |
| **Retrieval passage** | A **technical retrieval representation** derived from the above — not a new business entity |
| **Embedding** | A **technical vector representation** of a retrieval passage — not a new business entity |

**RAG infrastructure supports retrieval; it does not change the business
meaning of Raw Information Item, Relevant Information, Signal, or Summary.**
Those concepts, their lifecycle, and their ownership remain exactly as defined
in `docs/05-data-model.md`. No new "RAG document" business concept is
introduced — retrieval passages and embeddings are technical representations
used to make that existing content findable and answerable-from, nothing more.

---

## 13. Indexing / Ingestion Relationship

Conceptually, embeddings become relevant once content has entered the
knowledge base — the same point at which it becomes searchable and
answerable-from:

```text
Collection
   ↓
Normalization
   ↓
Deduplication
   ↓
Classification
   ↓
Relevance
   ↓
Importance / Signal decision
   ↓
Content becomes part of the knowledge base
   ↓
Embedding Generation
   ↓
Vector persistence
```

This document does not force a final, exact sequencing beyond what
`docs/03-technical-spec.md` Section 10.1 already establishes for the ingestion
pipeline; where that sequencing is not fixed (for example, exactly which
processing outcome triggers embedding generation, or whether embedding happens
synchronously within the same pipeline pass or as a separate step), it remains
an implementation/design question, not decided here.

**Embedding generation may be needed for more than one purpose:**

- **Semantic search / Grounded Q&A** — so relevant content can be found by
  meaning (Sections 7, 9, 10).
- **Near-duplicate processing** — the near-duplicate assessment capability may
  use embeddings/similarity as part of deduplication
  (`docs/06-ai-agents.md` Section 4.1; `docs/05-data-model.md` Section 18).

Whether these two uses share the same embeddings or use separate ones is not
fixed here. The detailed ingestion pipeline itself — connectors, stage
sequencing, and processing-state mechanics — belongs to `docs/08-ingestion.md`;
this document only addresses the RAG-relevant part of that flow (where
embedding generation and vector persistence conceptually fit).

---

## 14. RAG Failure Behavior

Conceptual failure points specific to the RAG pipeline, consistent with the
general failure taxonomy of `docs/03-technical-spec.md` Section 13 and the
capability-level failure behavior of `docs/06-ai-agents.md` Section 10:

| Failure | Conceptual behavior |
|---|---|
| Embedding provider unavailable | Treated as an AI/provider failure; the dependent operation (indexing, search, or a Q&A request) is left pending/failed rather than silently skipped |
| Embedding generation failure | Same as above; no vector is fabricated or substituted |
| Invalid embedding dimension | Treated as an invalid/malformed result (`docs/06-ai-agents.md` Section 4.6, item 7); rejected, not silently coerced |
| Vector persistence failure | A database/infrastructure failure at the Java persistence boundary; recorded and surfaced, not silently dropped (`docs/03-technical-spec.md` Section 13.1) |
| Retrieval failure | The user is told search or answering is temporarily unavailable; the query is preserved where applicable (`docs/02-functional-spec.md` Section 12.3, 13.3) |
| Insufficient retrieval context | Not a hard failure — an explicit, defined outcome: "insufficient information" for Q&A (`docs/02-functional-spec.md` Section 13.2) |
| Q&A provider failure | Treated as an AI/provider failure; the user is told answering is temporarily unavailable; no fabricated answer (`docs/02-functional-spec.md` W11) |
| Invalid structured Q&A output | Handled per the bounded repair and typed-error behavior already defined (`docs/03-technical-spec.md` Section 9.6; `docs/06-ai-agents.md` Section 7) |

**Behavior must remain honest in every case:**

- **No fabricated answer** is ever returned.
- **No invented citation** is ever produced — a citation always resolves to a
  passage actually supplied.
- **No silent fallback to unrelated external knowledge** occurs when
  retrieval or generation fails — the system reports the limitation
  (insufficient information, or temporary unavailability) instead
  (`docs/02-functional-spec.md` R2, R11-equivalent; `docs/06-ai-agents.md`
  Section 8).

No additional fallback system (a secondary retrieval source, a cached
"best-effort" answer mechanism, or similar) is introduced.

---

## 15. RAG Security / Trust Boundary

This section applies the security architecture already approved in
`docs/03-technical-spec.md` Section 20 to the RAG pipeline specifically; it
does not design a new subsystem.

- **External source content is untrusted input**, exactly as established for
  ingestion generally (`docs/03-technical-spec.md` Section 20.2). This applies
  equally to content once it becomes a retrieval passage: a passage is data
  derived from untrusted source content, not a trusted instruction.
- **Retrieved source content is data, not instructions.** The RAG pipeline
  must not treat text contained inside an article or source passage as a
  system instruction to the AI capability processing it — for example, text
  in a source that attempts to direct the summarization or Q&A capability's
  behavior is content to be summarized or cited, never a command to be
  obeyed (`docs/03-technical-spec.md` Section 20.1, "LLM output is untrusted
  input" applies symmetrically to LLM *input* drawn from external sources).
- **No separate prompt-security subsystem is designed here.** This remains an
  architectural principle enforced through the same structural controls
  already described — structured output, schema validation, and grounding
  discipline (`docs/06-ai-agents.md` Section 8; `CLAUDE.md` Section 16) — not
  a new component.

The principle stays architectural, not implementation-level:

- Source content is untrusted input.
- Provider/model output is validated (`docs/03-technical-spec.md`
  Section 8.3, 9.6).
- Provenance is preserved (Section 11).
- Java controls persistence and business state
  (`docs/04-architecture.md` Section 8.3, 9).

---

## 16. MVP Boundaries

**The MVP RAG design DOES:**

- semantic search over the knowledge base (Section 9);
- basic metadata filtering (Section 8);
- pgvector-based retrieval, owned by Java (Section 6, 7);
- source-grounded question answering (Section 10);
- provenance and citations traceable to the original source (Section 11);
- embedding generation through the `EmbeddingProvider` abstraction
  (Section 5).

**The MVP RAG design DOES NOT include:**

- open-web search;
- a general-purpose chatbot;
- autonomous agents;
- multi-hop retrieval;
- agentic retrieval loops;
- reranking;
- hybrid search (not already approved);
- external knowledge lookup;
- conversation memory;
- sophisticated personalization;
- a recommendation engine;
- forecasting;
- a knowledge graph;
- a separate vector database;
- a separate retrieval service.

This matches, and does not expand, the boundaries already fixed in
`docs/01-product-spec.md` Section 9, `docs/02-functional-spec.md` Section 2.2,
and `docs/03-technical-spec.md` Section 25.

---

## 17. Replaceability

The following remain replaceable, consistent with
`docs/03-technical-spec.md` Section 21 and `docs/04-architecture.md`
Section 11:

- the **embedding provider**;
- the **embedding model**;
- the **chunking strategy** (once one is chosen);
- the **vector index strategy**;
- the **retrieval implementation** (the exact query/filtering logic, as long
  as it continues to perform Java-owned similarity retrieval + metadata
  filtering);
- the **Q&A model/provider**.

**Business concepts remain stable** while these technical choices change:

```
Business model (Raw Information Item, Relevant Information,
                 Signal, Summary — docs/05-data-model.md)
      ↑
      │  stable contract
      ↓
RAG technical representation (retrieval passage, embedding)
      ↓
Embedding / retrieval implementation
      (model, index, chunking — replaceable)
```

A change of embedding model, chunking approach, or vector index strategy
should not require redesigning the Signal business model, and must not change
the meaning of Raw Information Item, Relevant Information, Signal, or Summary
(Section 12).

---

## 18. Open Questions

This document resolves none of the following; each is carried forward from
the specification that originated it, using existing identifiers only.

- **T3** — Embedding model and vector dimension
  (`docs/03-technical-spec.md` Section 24; `docs/06-ai-agents.md`
  Section 14; Section 5, 6 above).
- **T7** — pgvector index type and parameters
  (`docs/03-technical-spec.md` Section 24; Section 6 above).
- **T15 / Q12** — Near-duplicate similarity threshold, where it draws on
  embeddings shared with the RAG pipeline
  (`docs/02-functional-spec.md` Q12; `docs/03-technical-spec.md` Section 24,
  T15; Section 13 above).
- **Q14** — Summary form/length, relevant to what text is available as a
  retrieval-passage candidate (`docs/02-functional-spec.md` Q14;
  Section 4 above).
- **Q21** — Whether question answering ever supports multi-turn context
  (`docs/02-functional-spec.md` Q21; `docs/06-ai-agents.md` Section 14;
  Section 10 above).
- **T19 / Q24** — Future multilingual behavior, including how retrieval and
  Q&A would behave for non-English content
  (`docs/02-functional-spec.md` Q24; `docs/03-technical-spec.md` Section 24,
  T19).

**RAG-specific implementation/design questions not covered by an existing
project question ID** (explicitly marked as such, not assigned a formal
question identifier):

- The exact **chunking approach** (whether chunking is used at all, and if so
  its size/overlap/algorithm) — Section 4.
- The exact **placement of embeddings** (which record(s) — Relevant
  Information, Signal, Summary, or a derived passage — an embedding
  represents, and at what granularity) — Section 3, 6.
- The exact **retrieval parameters** (how many passages are retrieved per
  query, and any similarity cutoff beyond "semantic similarity") — Section 7.
- Whether **near-duplicate processing** and **semantic search/Q&A** share the
  same embeddings or use separate ones — Section 13.

None of these is closed by adopting a common industry default; each remains
open until a future document or explicit decision settles it.

---

## 19. Decisions vs. Open Questions

### Already decided (restated from prior documents, not new here)

- PostgreSQL + pgvector is the storage/retrieval layer; no separate vector
  database (`docs/03-technical-spec.md` Section 4.2–4.3, 11; Section 25).
- Python generates embeddings (`docs/06-ai-agents.md` Section 4.6).
- Java persists and retrieves embeddings/vectors (`docs/03-technical-spec.md`
  Section 9.8, 11.2–11.3).
- Java performs retrieval; Python never queries pgvector or PostgreSQL
  (`docs/03-technical-spec.md` Section 7.4, 9.8).
- Python performs grounded answer synthesis from passages Java supplies
  (`docs/06-ai-agents.md` Section 4.7).
- Question answering and search must be source-grounded, with provenance
  mandatory (`docs/01-product-spec.md` Section 3; `docs/02-functional-spec.md`
  R1, R2).
- Embedding and chat capabilities are reached only through the project-owned
  `EmbeddingProvider` / `LlmChatProvider` abstractions
  (`docs/03-technical-spec.md` Section 9.1–9.2; `docs/06-ai-agents.md`
  Section 9).
- No reranking in the MVP (`docs/01-product-spec.md` Section 8;
  `docs/02-functional-spec.md` R19).
- No open-web search (`docs/02-functional-spec.md` Section 13.1; Section 1
  above).
- No separate vector database, message broker, dedicated retrieval service,
  or other infrastructure beyond PostgreSQL/pgvector and the existing Java
  backend / Python AI service (`docs/03-technical-spec.md` Section 25).

### Conceptually defined here (this document's contribution)

- Retrieval passages are **technical representations**, not a new business
  entity (Section 3, 12).
- Embeddings represent retrievable text derived from existing business
  content; they are technical vector representations, not a source of truth
  (Section 3, 6, 11).
- Grounded Q&A receives retrieved passages, with provenance, and never
  retrieves on its own (Section 7, 10).
- Retrieval (Java) remains architecturally separate from answer synthesis
  (Python) (Section 2, 7, 10).
- RAG infrastructure supports retrieval without altering the business meaning
  or lifecycle of Raw Information Item, Relevant Information, Signal, or
  Summary (Section 12).

### Still open

Every item listed in Section 18, and only those items — no additional open
question is introduced beyond what is already known to be undecided.

---

## 20. RAG Core Contract Architecture

Introduced by **Task 8.1** (`docs/adr/0009-rag-core-architecture-and-contracts.md`).
This section records the contract-level architecture; Sections 1–19 still
describe the Signal Engine MVP behaviour those contracts will later be wired to
implement.

### 20.1 A reusable core, separate from the business

The RAG core lives in its own Java package tree, `org.signalengine.rag`, a peer
of `domain`, `application`, `infrastructure` and `interfaces`. It is a
self-contained library: a future project can depend on it without importing any
Signal Engine business concept.

The core depends on **nothing** outward:

- no Signal Engine business type — no `Signal`, `RelevantInformation`,
  `RawInformationItem`, `AreaOfInterest`, `Interest`, `Source`, no Signal Engine
  repository, no Signal Engine business rule;
- no framework — no Spring annotation or type, no `jakarta.*`/Hibernate, no SQL
  or JDBC, no HTTP client, no vector-store type, no LLM provider SDK, no model
  name;
- no infrastructure knowledge — the core cannot tell whether retrieval uses
  pgvector, Elasticsearch, OpenSearch, Qdrant, another store, or a remote
  service, nor which provider a generator calls.

Spring wires concrete implementations of these contracts in the infrastructure
layer, in later tasks (`docs/04-architecture.md` Section 4.1). An architecture
test (`RagCoreBoundaryTest`) enforces the import boundary.

### 20.2 The runtime pipeline is composed from stage contracts

```
Query
  → Query processing   [optional]   QueryProcessor
  → Retrieval                       Retriever
  → Reranking          [optional]   Reranker
  → Context assembly                ContextAssembler
  → Context refinement [optional]   ContextRefiner
  → Generation         [optional]   Generator
  → Answer validation  [optional]   AnswerValidator
```

Only `Retriever` and `ContextAssembler` are required. Each contract is one
narrow capability; each optional stage, when absent, is skipped and nothing else
changes. `StagedRagPipeline` composes whichever stages it is given, in this
fixed conceptual order, through a builder — it is not subclassed and it holds no
retrieval, ranking, assembly or generation logic of its own. The
"transform" stages (`QueryProcessor`, `Reranker`, `ContextRefiner`,
`AnswerValidator`) are composable via `andThen`, so a further transformation
(query expansion, diversity reranking, context compression, citation validation)
is added by wrapping the existing stage, never by editing another stage or the
pipeline class.

An abstraction exists here **only** where it is a genuinely replaceable pipeline
capability. No interface was created for its own sake; there is no "RAG service"
that contains every behaviour.

### 20.3 Independently exposable outputs

`RagExecution` is the immutable record of one run. It carries, each usable on
its own:

1. **Retrieval result** — `RetrievalResult`: ordered `RetrievedPassage`s, each
   with text, `Provenance`, a strategy-defined score, and open metadata.
2. **Assembled context** — `Context`: ordered `ContextPassage`s, provenance
   preserved. A caller can request `Query → Retrieval → Context` and stop, via
   `RagPipeline.assembleContext(Query)` — no generator is invoked even when one
   is configured. `Context` is deliberately **not** a formatted prompt; a
   generator renders it however it needs.
3. **Generated answer** — `RagAnswer`: answer text, an explicit
   answered / insufficient-evidence flag, and citations. Present only when a
   `Generator` ran.
4. **Provenance / citations** — `Provenance` is attached at retrieval and
   carried unchanged into every `ContextPassage` and then into every `Citation`
   on the answer. `Provenance` is generic: source identifier (mandatory),
   original URL, title, document identifier, passage identifier, and an open
   attribute map — no dependency on Signal Engine's `Source`, and no invented
   fields.
5. **Evaluation data** — the whole `RagExecution`, plus a per-stage list of
   which component played each role (`ComponentDescriptor`: component type,
   implementation id, version).

### 20.4 Evaluation is outside the runtime

```
RagExecution  →  Evaluator  →  EvaluationResult  →  (evaluation store, later task)
```

`Evaluator.evaluate(RagExecution)` is the entire contract. The evaluator is
handed a finished, immutable execution and **no** pipeline or stage reference,
so it cannot re-run, drive, or mutate the RAG. Nothing in the runtime pipeline
packages depends on the evaluation package. Evaluation never becomes a runtime
dependency of answering a query.

An evaluator may assess retrieval quality, context quality, answer relevance,
grounding / faithfulness and citation correctness — but Task 8.1 fixes **no
metric, no scoring scale, and no LLM judge**. `EvaluationFinding` carries a
dimension and a free-text observation only. Metrics and any judge approach stay
open (`docs/09-evaluation.md` Section 21).

The future improvement loop — evaluation results → improvement analysis →
experiment → evaluation → proposal → human approval → new RAG
configuration/version — is documented here but not modelled. An improvement step
compares `RagExecution`s and swaps components behind the existing contracts; it
never edits RAG core source.

### 20.5 Indexing is a separate concern

Indexing is not part of the runtime retrieval pipeline. The core defines only
the minimum future-facing boundary — `IndexingPipeline.index(IndexableContent)
→ IndexingReport` — with **no implementation**. The conceptual indexing pipeline

```
Content → Parsing → Chunking → Metadata enrichment → Embedding → Persistence
```

is entirely internal to a later implementation of that contract. The query-time
`RagPipeline` never depends on the indexing package. No chunking parameter,
embedding model, or vector dimension is decided.

**Task 8.2 implements the Chunking step of this pipeline** — see Section 21.
Parsing precedes it (a minimal text/Markdown extractor); metadata enrichment,
embedding and persistence remain later tasks.

### 20.6 Configuration and versioning

The only configuration surface the core defines is `ComponentDescriptor`
(component type + implementation id + version), which each implementation
reports about itself so a `RagExecution` records what produced it. This is not a
configuration framework, not a feature-flag system, and not model-training
infrastructure — none of those are introduced.

### 20.7 Where the selected libraries plug in later

Two libraries are recorded now as intended future adapters. Neither is a
dependency yet; neither is integrated in Task 8.1; both will sit **behind** the
core contracts.

| Library | Contract it implements behind | Role |
|---|---|---|
| `SlaouiSS/semantic-chunker` | inside a future `IndexingPipeline` implementation, at the Chunking step | splits parsed content into semantically coherent passages before embedding |
| `SlaouiSS/spring-ai-hybrid-retriever` | a future `Retriever` implementation (infrastructure, Spring-AI-based) | dense + sparse retrieval with score fusion, returning generic `RetrievedPassage`s |

Because both live behind a core contract, adopting or replacing either changes
no other stage and no core type.

### 20.8 Development LLM strategy (documented, not implemented)

RAG component quality will be evaluated against a real, controlled corpus and
real queries in later tasks. For development validation of the contracts and
prompts, a strong model reached through NVIDIA Build (free deployer) will be
used behind the `Generator` contract — via the Task 6A provider abstraction on
the Python side (`docs/adr/0006-ai-java-python-foundation.md`). The same
contracts and prompts are then exercised against local Ollama
(`CLAUDE.md` Section 3, 13). Changing the LLM provider must not require changing
any RAG contract. Task 8.1 implements neither NVIDIA nor Ollama integration.

### 20.9 Still open

Every implementation decision listed in Section 18 remains open, and in
particular: embedding model and vector dimension (T3), pgvector index and
distance function (T7), chunking approach and parameters, retrieval parameters,
reranking implementation, query transformation, context budget, evaluation
metrics, LLM judge, and multilingual behaviour. Task 8.1 resolves none of them.

---

## 21. Indexing and Semantic Chunking (Task 8.2)

Introduced by **Task 8.2** (`docs/adr/0010-indexing-foundation-and-semantic-chunking.md`).
This is the first real brick of the indexing side of RAG: it turns one unit of
normalized content into ordered, provenance-bearing passages. It implements no
embeddings, no vector storage, no retrieval, and no persistence.

```
IndexableContent  →  Chunker  →  Chunking { ordered Passages + which chunker produced them }
```

### 21.1 The chunking contract

`org.signalengine.rag.chunking` — still framework-free and business-free (an
architecture test forbids Spring, `jakarta`, Hibernate, SQL, HTTP, Jackson, the
chunker library, and every Signal Engine business package):

- **`Chunker`** — `Chunking chunk(IndexableContent content)`, plus a
  `ComponentDescriptor descriptor()`. One contract; fixed-size, recursive,
  Markdown-aware, HTML-aware, semantic and custom strategies all implement it,
  and no strategy's configuration type or library type appears on it.
- **`Passage`** — `passageId`, `ordinal`, `text`, `Provenance`, `metadata`. A
  retrieval/indexing unit, useful before any embedding exists. Deliberately
  distinct from `RetrievedPassage` (retrieval output) and `ContextPassage`
  (context output): a chunk is not a retrieval result.
- **`Chunking`** — the result: `contentId`, ordered `passages`, the
  `ComponentDescriptor` of the chunker that ran, `warnings`, and run `metadata`.
  The strategy is always explicit on the descriptor and on each passage's
  `chunkStrategy` metadata, so a structural result and a semantic result are
  never confused.

`RagComponentType` gains `CHUNKER`.

### 21.2 Two implementations, one contract

| Implementation | Where | Strategy | Needs |
|---|---|---|---|
| `StructureAwareChunker` | RAG core (no dependency) | Deterministic: blank-line blocks, headings start sections, packed to a size cap, never split inside a block | nothing |
| `SemanticChunkerAdapter` | infrastructure | Language-model boundary detection via `SlaouiSS/semantic-chunker` | the chunker library + a boundary model |

They share **only** the `Chunker` contract and the generic RAG models. The
structural chunker is a working default and a fixed baseline for later
comparison; it does **not** claim to be semantically equivalent to the semantic
chunker. Neither is `@Primary`; a later indexing task chooses per content.

### 21.3 The semantic chunker integration

- **Library:** `io.github.slaouiss:semantic-chunker-core:1.0.0` — Apache-2.0,
  Java 21, **no runtime transitive dependencies**. Only the `-core` module; the
  `-spring-ai`, `-tika` and `-unstructured` modules are not used.
- **Input side:** `NormalizedTextDocumentExtractor` (infrastructure) turns
  already-normalized `text/plain` / `text/markdown` into the library's document
  model — headings, paragraphs, list items — with correct provenance offsets. No
  Tika.
- **Model side:** `AiCapabilityChunkingModel` (infrastructure) implements the
  library's `ChunkingModel` SPI by forwarding the library-composed boundary
  prompt to the Task 6A `AiCapabilityInvoker` and the new
  **`semantic-chunk-boundary` v1** Python capability. **No Spring AI. No direct
  Ollama call from Java.** The library owns the `[N1,N2,…]` / `[]` response
  parsing, validation, and its bounded retry.
- **Provider:** entirely the Python service's configuration —
  `AGENTS_LLM_PROVIDER` selects NVIDIA Build (`nvidia`, new
  `NvidiaLlmProvider`, OpenAI-compatible) for development first, and local Ollama
  (`ollama`) later, with no change to the capability contract or the RAG
  architecture.

### 21.4 Metadata strategy

An **extensible but controlled** `Map<String,String>` with documented keys
(`IndexingMetadata`): stable common facts (`contentType`, `language`,
`publishedAt`) plus chunk detail (`chunkStrategy`, `chunkUnitCount`,
`chunkCharStart`/`End`) and run detail (`sourceCharLength`, `passageCount`). No
rigid record of speculative fields. Content-level metadata is copied onto every
passage, so a value survives `content → passage → (future embedding) → retrieval
→ context → citation`.

### 21.5 Provenance

Every passage keeps the content's generic `Provenance` (Section 20.3) with its
`passageId` set, so a chunk is always traceable to its source and citable. The
semantic chunker's own provenance (unit ordinals, offsets into normalized text)
is honoured internally but does not leak onto the generic `Passage`.

### 21.6 Deterministic passage identity

`passageId = SHA-256(length-prefixed: contentId | chunkerImplementationId |
chunkerConfigurationVersion | ordinal | text)`, hex-encoded. Idempotent (same
content + same configuration ⇒ same ids), content-sensitive, configuration-
sensitive (a config change changes the descriptor version and therefore the
ids), and position-sensitive. Not a random UUID.

### 21.7 Signal Engine adapter

`SignalEngineIndexableContent` (infrastructure) maps a `RawInformationItem` +
`Source` into the generic `IndexableContent`. It is the one place business types
meet the RAG contracts; it applies no business rule and changes no ingestion
behaviour. Nothing indexes Signal Engine content automatically yet.

### 21.8 Current configuration (all provisional)

| Setting | Default | Note |
|---|---|---|
| `signal-engine.rag.chunking.max-input-tokens` | 8192 | conservative; window planning never overflows a smaller real model |
| `signal-engine.rag.chunking.default-media-type` | `text/markdown` | assumed when content declares none |
| `signal-engine.rag.chunking.structure-aware-max-chars` | 1200 | target passage size for the baseline |
| `NVIDIA_MODEL` | `nvidia/nemotron-3-super-120b-a12b` | not a benchmarked choice |
| `AiCapabilityChunkingModel.estimateTokens` | `ceil(chars / 3)` | documented heuristic |

None is a tuned figure. The chunking parameters proper (passage size, overlap,
unit granularity) remain open.

### 21.9 Known limitations

- The semantic chunker is **language-model driven** — there is no heuristic
  mode; a real run needs a configured provider.
- `semantic-chunker` 1.0.0's `SemanticChunker` class javadoc is stale (claims
  the pipeline stages are unimplemented; they are not). `chunk(Path)` throws
  `UnsupportedOperationException`; the adapter uses `chunk(DocumentSource)`.
- Token usage is not observable through the Task 6A provider abstraction — a
  semantic run reports `(0, 0)`.
- A `RawInformationItem` carries no title, so `Provenance.title` is null at index
  time; a format-aware normalizer (Q1) would also set `contentType` precisely
  rather than defaulting to `text/plain`.
- The real-model quality observation is **pending** a provider (NVIDIA key or a
  running Ollama) — see ADR 0010.

### 21.10 Still open

Everything in Section 18, and in particular: embedding model / dimension (T3),
pgvector index and distance function (T7), chunk size / overlap / unit
granularity, retrieval parameters, hybrid retrieval, reranking, context budget,
evaluation metrics, LLM judge, the NVIDIA model id and key provisioning. Task 8.2
resolves none of them.

---

## 22. Embedding Contract, Local Provider and Model Benchmark (Task 8.3A)

Introduced by **Task 8.3A** (`docs/adr/0011-embedding-contract-and-local-model.md`).
This section adds the embedding abstraction, the local Ollama embedding provider,
and the result of a **real** benchmark run to inform open question **T3**. It
implements no pgvector, no retriever and no persistence.

### 22.1 The embedding contract

`org.signalengine.rag.embedding` — still framework-free and business-free (the
architecture test forbids Spring, `jakarta`, Hibernate, SQL, HTTP, Jackson and
every Signal Engine business package):

- **`EmbeddingModel`** — `EmbeddingResult embed(EmbeddingRequest)`, plus a
  `ComponentDescriptor descriptor()`. The contract names no runtime; Ollama,
  NVIDIA, another local runtime or an in-process model all implement it.
- **`EmbeddingRequest`** — `texts` plus a `TextRole` (`QUERY` / `PASSAGE`), so a
  model that documents asymmetric retrieval is used correctly. An empty list is a
  no-op; a blank text is rejected.
- **`EmbeddingResult`** — one vector per input in input order; the record
  enforces every guarantee (non-empty vector, one consistent dimension, all
  values finite) and **never truncates or pads**.
- **`EmbeddingException`** — the one failure type: provider failure, timeout, or
  invalid result.

`RagComponentType` gains `EMBEDDING_MODEL`.

### 22.2 Provider boundary and local Ollama strategy

Java never calls an embedding runtime directly. `AiCapabilityEmbeddingModel`
(infrastructure) implements `EmbeddingModel` by calling the **`embed` v1** Python
capability through the existing Task 6A `AiCapabilityInvoker` — the same
transport every other AI capability uses. No Spring AI, no second HTTP client.

On the Python side, `EmbeddingProvider` is a `typing.Protocol` sibling of
`LlmProvider`; `OllamaEmbeddingProvider` calls Ollama's `/api/embed` batch
endpoint and applies each model's documented query/passage prefix (nomic,
mxbai, snowflake-arctic, embeddinggemma need one; bge-m3 and granite do not).
Provider choice is configuration (`AGENTS_EMBEDDING_PROVIDER`), model choice is
configuration (`OLLAMA_EMBEDDING_MODEL`) — changing either changes no contract.

### 22.3 Verified candidate models

| Model | On Ollama | Params | Quant | Context | Dim | Download | License |
|---|---|---|---|---|---|---|---|
| bge-m3 | yes | 567M | F16 | 8192 | 1024 | 1.16 GB | MIT |
| nomic-embed-text (v1.5) | yes | 137M | F16 | 2048 | 768 | 274 MB | Apache-2.0 |
| mxbai-embed-large (v1) | yes | 334M | F16 | 512 | 1024 | 670 MB | Apache-2.0 |
| snowflake-arctic-embed2 (l-v2.0) | yes | 567M | F16 | 8192 | 1024 | 1.16 GB | Apache-2.0 |
| embeddinggemma (300m) | yes | 308M | BF16 | 2048 | 768 | 622 MB | Gemma Terms (commercial use permitted) |
| granite-embedding:278m (multilingual) | yes | 277M | F16 | 512 | 768 | 563 MB | Apache-2.0 |
| nemotron-3-embed-1b | **no** | — | — | — | — | — | — |
| llama-nemotron-embed-1b-v2 | **no** | — | — | — | — | — | — |
| llama-nemotron-embed-300m-v2 | **no** | — | — | — | — | — | — |

The three NeMo Retriever / `nemotron-embed` candidates are **not in the Ollama
library**. `llama-nemotron-embed-300m-v2` (formerly
`llama-3.2-nemoretriever-300m-embed-v2`) exists on NVIDIA Build, but that hosted
text-embedding API was **deprecated on 2026-05-18**, before this task, and no
NVIDIA credential is provisioned. With no local path and no live endpoint they
**could not be executed** and are reported `NOT AVAILABLE` — no numbers invented.

All six local models are practical on the target machine (32 GB RAM, Ryzen 5
5500U, no GPU): each is ≤ 567M parameters and ≤ 1.16 GB.

### 22.4 Benchmark: dataset, method, metrics

Real local inference through `OllamaEmbeddingProvider`. Harness, corpus and
queries: `agents/benchmarks/embedding/` (versioned `2026-09-07`).

- **Corpus:** 36 hand-written English passages (title + article-like body,
  metadata, area, source id/URL) resembling normalized chunked content, 6 per
  area of interest. Some passages deliberately share vocabulary across areas.
- **Queries:** 24 (4 per area) across six patterns — exact concept, paraphrase,
  implicit semantic relation, terminology variation, cross-wording, specific
  fact. Every relevance label is **human-authored** (grade 2 = directly
  relevant, grade 1 = partial); **no LLM assigned relevance**.
- **Held identical for every model:** corpus, queries, labels, passage text
  (`title\n\ntext`), cosine similarity, id-ascending tie-break, K ∈ {1,3,5,10}.
  The model is the only variable; the one model-specific input is the documented
  query/passage prefix.
- **Metrics:** Recall@{1,3,5,10}, MRR, nDCG@10 (graded, gain `2^grade − 1`).
  No composite score. Per-area and per-pattern breakdowns recorded.
- **Sanity checks per model:** repeat-embedding cosine ≈ 1.0 (determinism),
  dimension consistency across query/passage/long input, no NaN/Inf, long-input
  behaviour, query-vs-passage handling.

### 22.5 Real benchmark results

Executed 2026-09-07 on the target machine, Ollama 0.33.3, all six models via
local inference. Full data: `agents/benchmarks/embedding/results/2026-09-07.json`.

| Model | Runtime | Dim | R@1 | R@3 | R@5 | R@10 | MRR | nDCG@10 | Query ms | Corpus s | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|
| snowflake-arctic-embed2 | Ollama (local) | 1024 | 0.958 | 1.000 | 1.000 | 1.000 | **1.000** | 0.999 | 195 | 15.3 | OK |
| **embeddinggemma** | Ollama (local) | **768** | 0.958 | 1.000 | 1.000 | 1.000 | **1.000** | **1.000** | 123 | 5.3 | OK |
| granite-embedding:278m | Ollama (local) | 768 | 0.958 | 0.979 | 1.000 | 1.000 | **1.000** | 0.996 | 128 | 4.7 | OK |
| mxbai-embed-large | Ollama (local) | 1024 | 0.917 | 1.000 | 1.000 | 1.000 | 0.979 | 0.985 | 160 | 11.9 | OK |
| bge-m3 | Ollama (local) | 1024 | 0.917 | 1.000 | 1.000 | 1.000 | 0.979 | 0.983 | 168 | 13.6 | OK |
| nomic-embed-text | Ollama (local) | 768 | 0.917 | 0.979 | 1.000 | 1.000 | 0.979 | 0.982 | **76** | **4.7** | OK |
| nemotron-3-embed-1b | — | — | — | — | — | — | — | — | — | — | NOT AVAILABLE |
| llama-nemotron-embed-1b-v2 | — | — | — | — | — | — | — | — | — | — | NOT AVAILABLE |
| llama-nemotron-embed-300m-v2 | — | — | — | — | — | — | — | — | — | — | NOT AVAILABLE |

Query latency = mean of 24 single-query embeds (warm). Corpus s = one batch
embed of all 36 passages. R@1 aggregate is deflated by three multi-relevant
queries where a model correctly ranks the grade-2 passage first but the grade-1
partial cannot also occupy rank 1 — **MRR and nDCG@10 are the truer signals**.

**Reading the results.** The benchmark is **near-saturated**: R@5 = 1.000 and
R@10 = 1.000 for every model, and every retrieval failure comes down to a single
query — `q-ait-3` ("what is preventing new AI compute clusters from being built
on schedule?"), where three models rank *AI-accelerator export controls* above
*data-centre power availability*. On MRR and nDCG@10 a top tier of three
(`snowflake-arctic-embed2`, `embeddinggemma`, `granite-embedding:278m`) gets
every query's top hit right; the gap to the other three is that one query.
Per-pattern, all six are strong on exact-concept, terminology-variation and
specific-fact; the top tier is distinguished only on implicit-semantic.

**Sanity checks:** all six passed — deterministic (repeat cosine 1.0), no
NaN/Inf, dimension consistent across query/passage/long input.

### 22.6 Recommendation and dimension for Task 8.3B

**Recommended (provisional): `embeddinggemma`, dimension 768.** Among the
statistically-tied top tier it is the best practical fit for this machine and
for a future pgvector index:

- perfect nDCG@10 on all 24 queries; MRR 1.000;
- **768 dimensions** — smaller vector column, index and distance computation than
  the 1024-dim options, for no measurable quality loss here;
- fast (123 ms/query, 5.3 s for the 36-passage corpus) and small (622 MB);
- 2048-token context — comfortable headroom over the Task 8.2 chunk sizes
  (`structure-aware-max-chars` 1200 ≈ 300 tokens);
- built by Google explicitly for on-device retrieval, matching the no-GPU
  constraint.

**Fallback: `snowflake-arctic-embed2`, dimension 1024** — equal quality, 8192
context, multilingual, Apache-2.0. Choose it instead if the Gemma Terms are
judged unacceptable for the open-source distribution, or if long-context or
multilingual retrieval becomes a hard requirement. `granite-embedding:278m`
(768, Apache-2.0) is quality-equivalent but its **512-token context** leaves too
little headroom for larger chunks.

The provisional default is wired: `OLLAMA_EMBEDDING_MODEL=embeddinggemma`,
`signal-engine.rag.embedding.expected-dimension` left at `0` (no guard) until T3
is locked — set it to `768` at that point.

### 22.7 Still open

- **T3 stays formally OPEN.** The benchmark is near-saturated on a 36-passage
  hand-written corpus; the margin between the top three models is within noise.
  Before Task 8.3B commits a dimension to the schema, re-run the harness against
  a **larger corpus of real ingested and chunked Signal Engine content** and a
  broader query set, and confirm the recommendation holds.
- Whether search/near-duplicate share one embedding or use separate ones
  (Section 13) — unchanged.
- pgvector index type and distance metric (**T7**) — Task 8.3B.
- Multilingual behaviour — deferred; if it becomes a requirement the fallback
  model already covers it.
- A controlled re-embedding migration path for a future model/dimension change —
  Task 8.3B or later.

---

## 23. pgvector Persistence and Indexing (Task 8.3B)

Introduced by **Task 8.3B** (`docs/adr/0012-pgvector-persistence-and-indexing.md`).
This section records the concrete persistence layer for chunked, embedded
passages. It implements **no retrieval**, no query embedding, no reranking and no
semantic search.

```
IndexableContent → Chunker → passages → EmbeddingModel → rag_passage / rag_passage_embedding
```

### 23.1 The generic write port

`org.signalengine.rag.indexing` (still framework- and persistence-free; the
architecture test forbids Spring, JDBC, PostgreSQL, pgvector and every Signal
Engine business package):

- **`IndexedPassageStore`** &mdash; `PassagePersistOutcome save(IndexedPassage)`.
  The write-side counterpart of `Retriever`; PostgreSQL/pgvector sits behind it in
  infrastructure. Idempotent by identity; a changed chunker configuration or a
  changed embedding model coexists; nothing is deleted, truncated or padded.
- **`IndexedPassage`** &mdash; a chunked `Passage` plus its embedding, the chunker
  descriptor and the `EmbeddingModelDescriptor`. Identity = deterministic passage
  id (Task 8.2) + embedding model.
- **`StagedIndexingPipeline`** &mdash; the supplied `IndexingPipeline`: composes a
  `Chunker`, an `EmbeddingModel` and an `IndexedPassageStore`; returns an
  `IndexingReport` (passages, embedded, persisted, skipped, failed, notes). An
  embedding failure aborts the content; a per-passage persist failure is counted,
  not swallowed.

`EmbeddingResult.model()` is now a generic `EmbeddingModelDescriptor(provider,
model, version, dimension)` so persistence can record *which* model produced a
vector (Task 8.3A carried only an opaque descriptor).

### 23.2 The schema (migration V11)

| Table | Key | Holds |
|---|---|---|
| `rag_passage` | `id` = deterministic passage id (TEXT) | `content_id`, structured provenance (`source_id`, `document_id`, `origin_uri`, `title`), `chunker_id`/`chunker_version`, `passage_ordinal`, `passage_text`, `provenance_attributes`/`metadata` (JSONB), timestamps |
| `rag_passage_embedding` | `(passage_id, embedding_model, embedding_model_version)` unique | `passage_id` FK (cascade), `embedding_provider`/`embedding_model`/`embedding_model_version`, `embedding_dimension` (`CHECK = 768`), `embedding` `vector(768)`, timestamps |

No business-level "Document" entity. No triggers, no stored procedures. The
PostgreSQL adapter (`PgVectorIndexedPassageStore`, infrastructure) uses
`JdbcClient` for the `::vector` cast and the `ON CONFLICT` upsert &mdash; no
JPA/Hibernate.

### 23.3 Vector dimension and distance

- **Dimension: 768**, fixed. `vector(N)` is fixed-width in pgvector, so the column
  is `vector(768)` for the current provisional model (`embeddinggemma`, Section
  22). A different-dimension model needs a **new migration**, never an in-place
  change &mdash; the RAG core and the adapter reject any other length rather than
  truncate or pad.
- **Distance: cosine** (`<=>`), consistent with the Section 22 benchmark. Chosen
  now so the eventual index is unambiguous; retrieval itself is not built.
- **Index: none yet.** No HNSW/IVFFlat index &mdash; the MVP corpus is small
  enough for an exact scan and the index type/parameters are open question
  **T7**. The column is ready; a later migration adds
  `USING hnsw (embedding vector_cosine_ops)` when T7 is decided.

### 23.4 Idempotency and re-embedding

The logical identity is passage id (content + chunker + chunker configuration +
ordinal + text) plus the embedding model. Re-running the same content/config/model
refreshes in place; a changed chunker configuration or a changed embedding model
**coexists** with the previous rows &mdash; nothing is silently deleted. A model
swap: change `OLLAMA_EMBEDDING_MODEL`, re-index (new `rag_passage_embedding` rows
appear, old ones remain), and add a migration for the new `vector(N)` column when
the dimension differs. No RAG-core or business-logic change.

### 23.5 Still open

**T3** (embedding model / dimension &mdash; `embeddinggemma` / 768 is provisional
and wired; re-validate on real ingested content), **T7** (pgvector index type and
parameters), retrieval parameters, hybrid retrieval, reranking, context budget,
the re-embedding migration *procedure*, and shared-vs-separate embeddings for
near-duplicate and retrieval. Nothing in the backend indexes Signal Engine
content automatically yet &mdash; no scheduler, no use case, no API.

---

## 24. Semantic Retrieval (Task 8.3C)

Introduced by **Task 8.3C** (`docs/adr/0013-semantic-retrieval-pgvector.md`). This
section records the first real `Retriever`: the read counterpart of the Task 8.3B
write path. It implements **semantic retrieval only** — no reranking, no hybrid or
keyword retrieval, no query rewriting or expansion, no generation.

```
Query → EmbeddingModel (TextRole.QUERY) → query vector
      → PostgreSQL + pgvector, exact cosine scan (<=>)
      → Top-K RetrievedPassages (ordered nearest-first) → RetrievalResult
```

### 24.1 The generic contracts (unchanged except `Query.topK`)

`org.signalengine.rag.retrieval` and `org.signalengine.rag.query` stay
framework-, persistence- and business-free (the architecture test forbids Spring,
JDBC, PostgreSQL, pgvector, Jackson and every Signal Engine package):

- **`Retriever`** — `RetrievalResult retrieve(Query)`, reused verbatim from Task
  8.1.
- **`RetrievedPassage`** — passage id, text, `Provenance`, `score`, open
  `metadata`. Reused verbatim.
- **`Query`** — the **one** generic change this task made: a first-class
  `int topK` canonical field, alongside the existing query text and metadata
  filters. `DEFAULT_TOP_K = 5` (provisional, see §7/§18 — retrieval parameters are
  open), `MAX_TOP_K = 200` (a hard ceiling so a query can never request an
  unbounded scan). `topK` outside `[1, MAX_TOP_K]` is rejected by the constructor.
  No ranking formulas, user-preference scores, business importance or query
  planning were added.

Query embedding uses the **existing generic `EmbeddingModel`** with
`TextRole.QUERY`; no `QueryEmbeddingModel`, no EmbeddingGemma-specific or
provider-specific query type was introduced. The same model family that embedded
the passages embeds the query (§24.4).

### 24.2 The pgvector adapter

`PgVectorRetriever` (`org.signalengine.infrastructure.rag.retrieval`,
package-private, `@Repository`) implements the generic `Retriever`. It:

1. embeds `Query.text()` through the injected `EmbeddingModel`
   (`AiCapabilityEmbeddingModel` → `embed` Python capability → Ollama);
2. runs one SQL statement joining `rag_passage_embedding` to `rag_passage`
   (the Task 8.3B schema — **no new table, no second store**), computing
   `embedding <=> :queryVector` as `cosine_distance`;
3. filters to embeddings whose `(embedding_provider, embedding_model,
   embedding_model_version, embedding_dimension)` equal the query embedding's
   descriptor (§24.4);
4. `ORDER BY cosine_distance ASC, p.id ASC LIMIT :topK` — nearest first, id as a
   deterministic tie-break;
5. maps each row to a generic `RetrievedPassage`, preserving provenance
   (`source_id`, `origin_uri`, `title`, `document_id`, passage id,
   `provenance_attributes`) and passage `metadata`, and adding
   `contentId`, `embeddingProvider/Model/ModelVersion` and the raw
   `cosineDistance` to the passage metadata.

No PostgreSQL, pgvector or JDBC type crosses the `Retriever` boundary. The generic
contract is reusable in another project with a different store.

### 24.3 Distance and score convention

pgvector's cosine **distance** operator `<=>` is used directly (`0` = identical
direction, `1` = orthogonal, `2` = opposite); lower is more similar and results
are ordered ascending. The public `RetrievedPassage.score()` is the documented
conversion **`score = 1.0 − cosineDistance`** (higher = more similar); the raw
distance is kept verbatim in the passage metadata under `cosineDistance`, and the
convention is also recorded in `RetrievalResult.metadata()` (`scoreConvention`).
No proprietary score, no similarity threshold, no filtering of "low-looking"
matches — Top-K always returns the best available candidates.

### 24.4 Embedding-model compatibility

The current provisional model is `embeddinggemma` / 768 dimensions (§22, T3) —
**not** hardcoded in the generic core. The adapter selects only stored embeddings
whose persisted model identity (`embedding_provider`, `embedding_model`,
`embedding_model_version`, `embedding_dimension`) matches the descriptor returned
when the query was embedded. A passage carrying vectors from a different embedding
model is never compared against the query. If several models coexist for a
passage (Task 8.3B allows this), only the matching one is used; if no compatible
embedding exists the result is simply empty. A model swap therefore needs no
change to the `Retriever` abstraction — re-index under the new model identity and
queries follow automatically.

### 24.5 Metadata filtering (minimal, generic only)

`PgVectorRetriever` honours two **generic provenance-identity** filter keys from
`Query.metadataFilters()` — `contentId` and `sourceId` — as exact-match
constraints on real columns. Any other key (e.g. `area`, `interest`) is a Signal
Engine **business** concept, cannot live in the generic core, and is recorded
under `RetrievalResult.metadata()` `ignoredFilters` rather than applied. Area /
interest / time-window filtering is **deferred** (it needs a schema change or a
filter DSL, neither of which this task introduces). Semantic retrieval is the
goal here.

### 24.6 No ANN index (intentional)

No HNSW or IVFFlat index is added. The corpus is small, retrieval is not yet
benchmarked at scale, the index type/parameters are open question **T7**, and an
**exact** scan is a clean, reproducible reference baseline for a later ANN
comparison. Verified by an integration test that asserts no `hnsw`/`ivfflat`
index exists on `rag_passage_embedding` and retrieval still works.

### 24.7 Retrieval benchmark (real, end-to-end)

`PgVectorRetrievalBenchmark` (`@Tag("benchmark")`, `./gradlew retrievalBenchmark`)
reuses the **Task 8.3A corpus and human-authored relevance judgments verbatim**
(`agents/benchmarks/embedding/{corpus,queries}.json` — 36 passages, 24 queries, 6
areas). It embeds the passages with the real `embeddinggemma` model, indexes them
through the real `PgVectorIndexedPassageStore` into a real PostgreSQL + pgvector
(Testcontainers), and answers every query through the real `PgVectorRetriever` —
validating the **complete path**, not the embedding model alone. Metrics mirror
the 8.3A `metrics.py`. Result written to `build/benchmark/retrieval-<date>.json`.

Run of 2026-09-07 (`embeddinggemma` / 768, exact cosine, `topK = 10`):

| Metric | End-to-end pgvector (8.3C) | Embedding-only baseline (8.3A) |
|---|---|---|
| Recall@1 | 0.958 | 0.958 |
| Recall@3 | 1.000 | 1.000 |
| Recall@5 | 1.000 | 1.000 |
| Recall@10 | 1.000 | 1.000 |
| MRR | 1.000 | 1.000 |
| nDCG@10 | 1.000 | 1.000 |
| Avg query latency | 123 ms (min 105 / p50 121 / p95 145 / max 146) | 123 ms |

Per area (Recall@1): Architecture 1.00, Business 1.00, Fashion 1.00, Law 1.00, AI
& Technology 0.875, Markets & Investment 0.875. No query failed to retrieve a
relevant passage; every query's first relevant passage is at rank 1 (the two sub-1
Recall@1 values are queries with **two** graded-relevant passages where one is at
rank 1 and the other within rank 3 — identical to the 8.3A pattern). Latency is
dominated by the query-embedding HTTP call to the Python service; the pgvector
exact scan over 36 vectors is sub-millisecond.

The end-to-end numbers **match** the embedding-only baseline within float32
rounding, which is the expected result: storing the same vectors in pgvector and
ordering by `<=>` reproduces the in-memory cosine ranking. This confirms the Java
DB interaction, the `1 − distance` score conversion, provenance/metadata
carry-through and determinism, not that `embeddinggemma` is permanently chosen.

### 24.8 Limitations and still open

- The benchmark corpus is small (36 passages) and hand-written; it is a
  **regression and wiring check**, not evidence that retrieval quality holds on
  real ingested content at scale. **T3** re-validation stays open.
- **T7** (pgvector ANN index type and parameters) remains open; exact scan is the
  deliberate baseline.
- **Hybrid retrieval** (dense + BM25/keyword) and **reranking** are **deferred** —
  not needed at current quality and explicitly out of scope here.
- **Query transformation** (rewriting, expansion, HyDE) is deferred.
- Area / interest / time-window **metadata filtering** is deferred (§24.5).
- Context assembly, generation, answer synthesis and the evaluation subsystem are
  later RAG stages, untouched by this task.

---

## 25. Context Assembly (Task 8.4)

Introduced by **Task 8.4** (`docs/adr/0014-rag-context-assembly.md`). This
section records the first real `ContextAssembler`: the stage after retrieval.

```
RetrievalResult → ContextAssembler → Context
```

### 25.1 Responsibility

Context assembly turns retrieved passages into a **coherent, bounded set of
evidence**. It removes exact-duplicate passages, keeps as many whole passages as
a budget allows, and preserves provenance. It does **not** decide truth or
importance, generate an answer, rerank, change retrieval scores, summarise,
compress, or rewrite source text — those are other stages or other tasks.

### 25.2 Context is a structured, first-class object

`Context` stays a structured record — an ordered `List<ContextPassage>` plus a
generic `Map<String,String>` of assembly metadata — **not** a single formatted
string. Each `ContextPassage` keeps the passage id, the verbatim passage text,
the `Provenance` carried unchanged from retrieval, and per-passage metadata. The
retrieval score and the 0-based retrieval position are copied into that
per-passage metadata (`retrievalScore`, `retrievalRank`) so the context is
traceable back to retrieval; the score is deliberately **not** promoted to a
`ContextPassage` field, because after assembly it is provenance-of-retrieval, not
a ranking signal. `Context` knows nothing about any LLM, prompt format, provider
or Signal Engine concept. A future `Generator` renders it into a prompt however
it needs; a caller can also request `Query → Retrieval → Context` and stop
(`RagPipeline.assembleContext`).

### 25.3 Ordering policy

**Retrieval order is preserved exactly.** The assembler introduces no ranking of
its own — not by score, source, recency, area, or any business attribute. It
relies on the deterministic order the `Retriever` already guarantees (Task 8.3C:
`ORDER BY cosine_distance ASC, p.id ASC`). The policy is recorded in the context
metadata (`orderingPolicy = retrieval-order`).

### 25.4 Exact-duplicate policy

If the same `passageId` appears more than once in the retrieval result, the
**first occurrence is kept and later ones dropped**; the count is reported
(`duplicatePassageIdsRemoved`). No semantic or near-duplicate detection is done
here — that is Task 6B's concern for a different purpose and stays out of the
generic RAG core. Two passages with identical text but different ids are both
kept. This limitation is deliberate.

### 25.5 Context budget

The context is always bounded. `ContextBudget` is a validated value object with
an explicit default (`DEFAULT_MAX_CHARACTERS = 12000`, provisional) and a hard
ceiling (`MAX_MAX_CHARACTERS = 200000`). The unit is **characters**, chosen
deliberately: the RAG core carries no tokenizer, a character count is exact and
deterministic, and it is **model-independent** — it is *not* an Ollama / NVIDIA /
OpenAI context window and is not derived from any model's prompt limit. A future
generator that renders the context for a specific model applies its own,
tighter, model-specific limit on top. The budget counts only selected passage
text; separators and instructions a generator adds are the generator's budget.
The metadata also exposes a rough `estimatedTokens` (≈ characters / 4) purely as
a convenience — it is not a tokenizer count and the budget is never enforced in
it. On the Java side the budget is configured by
`signal-engine.rag.context.max-context-characters`.

### 25.6 Budget selection behaviour

Passages are considered in retrieval order. A passage is included if the running
character total stays within the budget; a passage that would not fit is
**skipped and the next one is still considered** (first-fit), so as many whole
passages as possible are kept. A passage is **never split, truncated, or
summarised** to fill remaining space. A passage whose own text exceeds the entire
budget is excluded and reported (`skippedOversized`); if that leaves nothing, the
`Context` is legitimately empty (the same state as an empty retrieval) and the
metadata explains why. The metadata reports `retrievedPassages`,
`selectedPassages`, `usedCharacters`, `skippedForBudget`, `skippedOversized` and
`duplicatePassageIdsRemoved`.

### 25.7 Provenance and source-text integrity

Every `ContextPassage` carries the `Provenance` from its `RetrievedPassage`
**unchanged** (source id, origin URI, title, document id, passage id, attribute
map). The chain *context passage → retrieved passage → stored `rag_passage` →
original source* is unbroken. The assembler **never modifies passage text** — no
paraphrasing, summarising, rewriting, merging, or added content. If the context
would be too large, fewer complete passages are selected.

### 25.8 Why compression and `ContextRefiner` are deferred

Shrinking passage text to fit more evidence (summarisation, LLM compression,
near-redundancy removal, diversification) is real work with its own quality and
grounding risks. It belongs in a separate `ContextRefiner` stage, which the RAG
core already models as an **optional, composable** step *after* assembly. Task
8.4 implements **no** `ContextRefiner` — the seam exists, every pipeline may omit
it, and the assembler never depends on it.

### 25.9 Pipeline integration

`StagedRagPipeline` already runs `retriever → contextAssembler (→ contextRefiner?
→ generator?)`. Task 8.4 wires the real `BudgetedContextAssembler` and composes a
minimal query-time `RagPipeline` bean (`PgVectorRetriever` +
`BudgetedContextAssembler`, no generator), so `assembleContext(Query)` returns a
real assembled `Context`. Nothing consumes that bean yet — there is no query API.

### 25.10 Reusability

`ContextAssembler`, `Context`, `ContextPassage`, `ContextBudget` and
`BudgetedContextAssembler` are all in the framework-free RAG core. Another
project supplies its own `Retriever`, gets `RetrievedPassage`s, and calls the
assembler — with no dependency on Spring, PostgreSQL, pgvector, an HTTP client,
or any Signal Engine type. The architecture test enforces this.

### 25.11 Validation

Unit tests cover the 17 required behaviours (empty result, single/multiple
passages, order preserved despite scores, exact-duplicate removal, provenance and
metadata preserved, retrieval score preserved, budget respected, whole-passage
boundary, oversized-passage behaviour, invalid budget rejected, determinism,
selected/skipped counts, no text modification, no semantic dedup, generic
`Context`) plus pipeline composition. An integration test runs `Query →
PgVectorRetriever → BudgetedContextAssembler → Context` against real PostgreSQL +
pgvector: provenance, ordering and retrieval score survive, topK and the budget
interact, duplicate ids collapse, no database type leaks. Assembly of a 12-passage
retrieval result takes well under a millisecond — negligible next to the
query-embedding call — so no dedicated benchmark is warranted.

### 25.12 Limitations and still open

- **Compression / summarisation** and any real **`ContextRefiner`** — deferred.
- **Generation / answer synthesis** — the next RAG stage, untouched.
- **Reranking, hybrid retrieval, query transformation** — deferred (§24.8).
- **Token-accurate budgeting** — deferred; would need a tokenizer the RAG core
  deliberately does not carry. The character budget is the honest interim unit.
- Semantic / near-duplicate passage collapsing in the context — deferred.
- Per-source or per-document caps, diversity constraints — not modelled;
  add later behind `ContextAssembler` or `ContextRefiner` if a need appears.

---

## 26. Grounded Generator (Task 8.5)

Introduced by **Task 8.5** (`docs/adr/0015-rag-grounded-generator.md`). This
section records the first real `Generator`: the stage after context assembly. It
implements §10 (Grounded Question Answering) and adds nothing to
`docs/06-ai-agents.md` §4.7.

```
Query + Context → Generator → RagAnswer (grounded, with citations, or "insufficient")
```

### 26.1 Responsibility

The generator receives the query and the assembled `Context`, asks an LLM to
answer **using only that evidence**, and returns a structured `RagAnswer`. It does
**not** retrieve, rank, modify the context, summarise it, or reach PostgreSQL, the
internet, or any Signal Engine business object.

### 26.2 The stage is a thin infrastructure adapter over the Task 6A path

`AiCapabilityAnswerGenerator` (`org.signalengine.infrastructure.rag.generation`)
implements the generic RAG core `Generator`. It calls a new **`answer`** Python
capability (contract v1) through the existing Task 6A `AiCapabilityInvoker` — no
second LLM provider architecture, no direct Ollama/NVIDIA call, no provider code
in the RAG core. The concrete model, provider and prompt are the Python service's
configuration; the same generator works with Ollama, NVIDIA Build, or another
compatible provider without a RAG-core change. Only the capability contract
version is bindable on the Java side
(`signal-engine.rag.generation.contract-version`).

### 26.3 How the context is supplied

The adapter serialises each `ContextPassage` as a delimited block that keeps its
**stable `passageId`** identifiable, with the passage text and a short plain
source label (never a fabricated URL):

```
--- PASSAGE passageId="<id>" (source: <label>) ---
<verbatim passage text>
```

The passages go into a `CONTEXT` section of the user prompt, explicitly framed as
untrusted retrieved data (§26.6). The LLM never invents identifiers — it can only
cite ids that appear in the block.

### 26.4 Structured output and citations

The `answer` capability returns structured JSON, validated against a Pydantic
model with one bounded repair:

```json
{"answered": true, "answer": "...", "citations": [{"passageId": "..."}]}
```

The model returns **passage ids only**. Java attaches each `Citation`'s
`Provenance` from the matching `ContextPassage` — provenance is never taken from
the model, so a citation can never carry a fabricated source. `RagAnswer` and
`Citation` (Task 8.1) were sufficient and unchanged; the only RAG-core addition is
`GenerationException` (§26.7).

### 26.5 Citation validation — structural, in two layers

1. **Python `answer` capability** (`extra_check` in the bounded-repair loop):
   every cited id must be one that was supplied; an answered response must cite at
   least one passage; an insufficient response must cite none; no id cited twice.
   A violation triggers the one repair; a second failure is `AI_OUTPUT_INVALID`.
2. **Java** — the adapter re-checks (never trusts the model): a citation to an
   unknown passage, an answered response with no citation, or an
   insufficient-evidence response that cites, becomes a `GenerationException`.
3. **`GroundingAnswerValidator`** (RAG core, the pipeline's `AnswerValidator`
   stage) — a deterministic structural pass **independent of the generator**:
   drops a citation that does not resolve to a supplied passage or whose
   provenance does not match, collapses duplicates, and **downgrades** an answered
   response left with no valid citation to insufficient evidence (replacing the
   unsupported text). Every correction is recorded in the answer metadata.

This is **structural grounding**: it guarantees every surviving citation points
at a real supplied passage and that an "answered" verdict has at least one such
citation. It does **not** verify that the answer *text* is factually entailed by
the cited passages — that is semantic faithfulness, a separate evaluation concern
(§26.9, `docs/09-evaluation.md`). No prompt or validator makes grounding
mathematically guaranteed; the architecture makes an unsupported answer difficult
to produce and easy to observe.

### 26.6 Insufficient context and prompt injection

- **Insufficient context.** An empty `Context` short-circuits to `answered =
  false` with no LLM call. Otherwise the prompt instructs the model to set
  `answered = false` and cite nothing when the passages do not support an answer;
  the validation layers enforce that an insufficient answer carries no citations.
  No forced answer is ever produced.
- **Prompt injection.** Retrieved passage text is **untrusted data**. The system
  prompt states that the CONTEXT passages may contain text that looks like
  instructions and that the model must never follow it — the passages are material
  to answer from, nothing more. A deterministic test passes a passage containing
  "IGNORE ALL PREVIOUS INSTRUCTIONS …" and asserts it reaches the model only
  inside the CONTEXT block and that the grounding policy holds.

### 26.7 Provider / repair failure

A provider outage, a timeout, or output still invalid after the one repair all
arrive as a typed `AiCapabilityOutcome.Failed` and become a
`GenerationException` (unchecked, like `EmbeddingException` / `IndexingException`)
— distinct from the insufficient-evidence `RagAnswer`. The generator never
retries and never fabricates an answer. `StagedRagPipeline.execute` propagates it;
a future query API maps it to "answering is temporarily unavailable".

### 26.8 Pipeline integration

`StagedRagPipeline` already supported an optional `generator` and
`answerValidator`; no redesign was needed. `execute(Query)` now runs
`retriever → contextAssembler → generator → answerValidator` when a generator is
configured, and returns a `RagExecution` carrying query, retrieval, context and
answer; without a generator it still returns retrieval + context and no answer.
`assembleContext(Query)` never invokes the generator. The generation stage is a
first-class `StageExecution` with `durationMillis` and `answered` notes (no
prompt or answer text is stored there). The application composes a `RagPipeline`
bean (retriever + assembler + generator + grounding validator); nothing consumes
it yet — there is no query API.

### 26.9 Real-model check

An opt-in manual test (`RAG_ANSWER_REALMODEL=true`, needs the Python service with
a chat model; never in normal CI, no secret) runs the real `answer` capability.
Observed with a local `qwen3:14b`: a question the context supports is answered and
cites the correct passage; a question it does not support returns `answered =
false` with no citation; with two conflicting passages every kept citation still
resolves to a supplied passage.

### 26.10 Limitations and still open

- **Semantic factuality / faithfulness evaluation** — an LLM judge, faithfulness
  or citation-precision scoring, a golden-answer suite — **deferred** to the
  separate, later, asynchronous `Evaluator` concern. Generation validation here
  is structural only.
- **Per-claim citations / supporting spans** — `RagAnswer` carries answer-level
  citations; `Citation.quotedText` is available but the v1 capability does not
  populate it. Claim-level attribution is a later refinement.
- **Multi-turn / conversational context** — out of scope (`docs/02-functional-spec.md`
  Q21); each question is independent.
- **Answer length / format, "no advice" enforcement beyond the prompt** — the
  prompt forbids advice and predictions; measuring compliance is evaluation's job.
- **A larger real-world generation benchmark** — not built; the retrieval
  benchmark (§24.7) covers retrieval quality, and generation quality is an
  evaluation concern.
- **`ContextRefiner` / compression, reranking, hybrid retrieval, query
  transformation, ANN index (T7)** — all still deferred (§24.8, §25.12).

---

## 27. RAG Evaluation (Task 8.6)

Introduced by **Task 8.6** (`docs/adr/0016-rag-evaluation.md`). This implements
the independent evaluation capability the RAG core reserved in §20.4. It is
`docs/09-evaluation.md` §11 made concrete for retrieval and grounding; it adds no
new RAG concept.

```
RagExecution (immutable, finished)  +  RagEvaluationDataset  →  Evaluator  →  EvaluationResult
```

### 27.1 Independence

The evaluator is **outside** the runtime pipeline (§20.4). It receives only a
finished, immutable `RagExecution` and a hand-authored dataset; it is handed no
pipeline and no stage, re-runs nothing, touches no database, calls no model, and
uses **no LLM judge**. Nothing in the runtime packages depends on
`org.signalengine.rag.evaluation`. The `EvaluationIndependenceTest` and
`RagCoreBoundaryTest` enforce both facts.

### 27.2 The model

- **`Evaluator`** — `EvaluationResult evaluate(RagExecution)`, reused unchanged.
- **`EvaluationDimension`** — reused; one value added, `EXECUTION_OUTCOME`
  ("can this run be evaluated at all").
- **`EvaluationFinding`** — reused unchanged: a dimension + a free-text
  observation + open details.
- **`EvaluationMetric`** *(new)* — a named deterministic number:
  `(dimension, name, value, details)`. Flat, no verdict, no weighting.
- **`EvaluationResult`** — one additive field, `List<EvaluationMetric> metrics`,
  parallel to `findings`; plus `metricValue(name)`. **No combined "overall RAG
  score"** — a caller derives one if it wants.
- **`RelevanceJudgement`** *(new)* — for one query: `{relevantPassageGrades
  (passageId → grade ≥ 1), answerable}`. Grades follow the Task 8.3A convention
  (2 = directly relevant, 1 = partial).
- **`RagEvaluationDataset`** *(new)* — `{version, List<RelevanceJudgement>}`,
  looked up by exact query text. **Shape only** — it reads no file and parses no
  format (the core stays framework-free).
- **`RagExecutionEvaluator`** *(new)* — the supplied `Evaluator`.
- **`RagEvaluationSummary`** *(new)* — `mean(results, metricName)` /
  `count(...)`: a corpus number (MRR, mean recall@k, mean nDCG) is the plain mean
  across the results that carry the metric.

### 27.3 Deterministic metrics

| Dimension | Metric(s) | When |
|---|---|---|
| `RETRIEVAL_QUALITY` | `recall@1`, `recall@3`, `recall@5`, `recall@10`, `reciprocalRank`, `ndcg@10` | the dataset judges this query and it has relevant passages |
| `ANSWER_RELEVANCE` | `answerabilityAgreement` (1.0 / 0.0) | the dataset judges this query and an answer was generated |
| `CITATION_CORRECTNESS` | `citationValidity` (resolved citations / total) | an answer was generated and it carries citations |

Ranking metrics use the same definitions as the Task 8.3A/8.3C retrieval
benchmark, so an evaluator number is comparable to a benchmark number. `nDCG@10`
uses gain `2^grade − 1` and discount `log2(rank + 1)`.

### 27.4 Grounding / citation validity — structural, reusing the existing model

For every `Citation` on the answer, the evaluator checks that its `passageId`
resolves to a passage in the execution's own `Context` and that the citation's
`Provenance` matches that passage's. It reuses `Citation` and the context/passage
model directly — no second citation representation. It **observes** (findings +
`citationValidity`); it does not correct — correction is the runtime
`GroundingAnswerValidator`'s job (§26.5). This confirms **structural** grounding
only, not semantic faithfulness (§27.6).

### 27.5 Answerability behaviour

The dataset marks each query `answerable` or not. The evaluator compares that to
what the system did — `answer != null && answer.answered()` — and records
`answerabilityAgreement`. An unsupported question handled correctly is an
abstention (`RagAnswer.answered == false`), the existing contract; the evaluator
introduces no new behaviour. Answering a query judged unanswerable is also a
`GROUNDING_FAITHFULNESS` finding (a possible ungrounded answer).

### 27.6 The evaluation dataset concept

A dataset is small, explicit, hand-authored, checked in, and **never production
data** — so evaluation is reproducible and independent of what happens to be in
the database. The core defines only the in-memory shape; the tests build one from
a versioned JSON fixture (`src/test/resources/rag/evaluation/retrieval-eval-v1.json`)
through a **test-only** loader. Passage ids in the fixture are synthetic and
belong only to the fixture.

### 27.7 What is intentionally NOT evaluated yet

- **Semantic answer faithfulness / factuality** — whether the answer text is
  actually entailed by the cited passages. Needs an LLM-as-a-judge or a labelled
  answer set; **deferred**, and the judge approach is an open decision.
- **Answer relevance / quality, summary quality** — semantic, deferred.
- **Context sufficiency** as a semantic judgement (beyond "context is non-empty")
  — deferred.
- **Multilingual evaluation** — English only (`docs/02-functional-spec.md` Q24).
- **Automatic pass/fail thresholds, regression gates** — the evaluator reports
  numbers; deciding what is "good enough" is an open decision.
- **Production evaluation scheduling / persistence / a dashboard** — out of
  scope; `EvaluationResult` carries what a later persistence task needs.
- **A single combined RAG score** — deliberately not produced.
- **The canonical dataset format and storage** — open decision.

---

## 28. Summary

Signal Engine uses a **simple PostgreSQL/pgvector-based RAG pipeline**, sized
to exactly two capabilities: semantic search and grounded question answering.
Java owns retrieval and business state — it prepares queries, performs
similarity search with basic metadata filtering over pgvector, and supplies
the resulting passages, with their provenance, to Python. Python generates the
embeddings that make retrieval possible and synthesizes grounded answers
strictly from the passages it is given, never from the open web, hidden
knowledge, or its own memory.

Retrieval passages and embeddings are **technical representations** of the
existing business concepts — Raw Information Item, Relevant Information,
Signal, and Summary — not new business entities; changing the embedding
model, chunking approach, or index strategy does not change what those
concepts mean. Every answer, and every search result, remains traceable to the
collected source material it came from.

No sophisticated RAG technique — reranking, multi-hop retrieval, agentic
retrieval loops, query rewriting, HyDE, hybrid search, or a knowledge graph —
is introduced. No separate vector database, search engine, document store, or
retrieval microservice is introduced. The embedding model, its vector
dimension, the pgvector index strategy, the chunking approach, and several
related implementation questions remain **explicitly open**, to be resolved by
future technical or implementation work — not by this document.
