# ADR 0009 — RAG Core Architecture & Contracts

Status: Accepted
Date: 2026-09-07
Phase: 2 (docs/11-roadmap.md), Task 8.1 — the reusable RAG core foundation

---

## Context

`docs/07-rag.md` fixes Signal Engine's RAG *behaviour* (semantic search and
grounded Q&A over PostgreSQL/pgvector) but writes no classes. Phases 7–8 will
implement chunking, embeddings, retrieval, and grounded synthesis. Before that,
Task 8.1 establishes the **architecture and contracts** those phases plug into.

Requirements specific to this task:

- The RAG core must be **reusable by a future project** without importing any
  Signal Engine business concept.
- It must expose, each independently: retrieval results, assembled context,
  generated answers, provenance/citations, and evaluation data.
- Optional stages must be insertable before/after existing stages without
  rewriting unrelated components (`CLAUDE.md` Section 11, 29).
- Evaluation must be structurally outside the runtime pipeline (`CLAUDE.md`
  Section 17; `docs/09-evaluation.md` Section 2).
- Indexing must stay a separate concern.
- No implementation of retrieval, embeddings, generation, evaluation, or
  indexing — contracts and composition only.
- No Spring, no provider SDK, no infrastructure type in the core.
- Keep the model minimal; create an abstraction only where it is a genuine
  replaceable pipeline capability (`CLAUDE.md` Section 18).

## Problem

Where does the reusable RAG core live, and what is the minimal set of contracts
that lets `Query → Retriever → ContextAssembler → Generator` today become
`Query → QueryProcessor → HybridRetriever → Reranker → ContextAssembler →
Generator → Validator` later, with every stage swap staying local?

## Options considered

### Package placement

1. **A peer package `org.signalengine.rag`**, parallel to `domain` /
   `application` / `infrastructure` / `interfaces`, framework-free and
   business-free. *(chosen)*
2. A sub-package under `application` (`application.rag`). Rejected: the
   application layer carries Signal Engine use cases and depends on the domain;
   nesting the reusable core there blurs the "no business concept" boundary and
   invites accidental coupling.
3. A separate Gradle module. Rejected for now: real extraction value, but it is
   packaging ceremony the task does not need — the peer package already makes
   the boundary explicit and an architecture test enforces it. A module can be
   split out later with no code change.
4. A Python `rag/` package. Rejected: the task says to create one only if
   genuinely justified; the RAG orchestration is Java-owned (`docs/07-rag.md`
   Section 2), and Python's role (embeddings, synthesis) is already reached
   through the Task 6A capability path. The extension point is documented, not
   built.

### Composition model

1. **Fixed conceptual stage order, each stage a narrow contract, assembled by a
   builder; "transform" stages composable via `andThen`.** *(chosen)*
2. A generic `Stage<I,O>` pipeline. Rejected: loses type safety, makes
   `Context` and `RagAnswer` anonymous positions in a chain, and tempts a
   god-object executor.
3. Inheritance (`AbstractRagPipeline` with overridable hooks). Rejected by
   `CLAUDE.md` Section 18 and the task ("prefer composition over inheritance").

## Decisions

### 1. `org.signalengine.rag` — a framework-free, business-free peer package

Ten packages, each with a `package-info.java` stating its boundary:

| Package | Contents |
|---|---|
| `rag` | `RagComponentType`, `ComponentDescriptor`, the boundary `package-info` |
| `rag.query` | `Query`, `QueryProcessor` |
| `rag.retrieval` | `Retriever`, `RetrievedPassage`, `RetrievalResult`, `Reranker` |
| `rag.context` | `Context`, `ContextPassage`, `ContextAssembler`, `ContextRefiner` |
| `rag.provenance` | `Provenance`, `Citation` |
| `rag.generation` | `Generator`, `RagAnswer`, `AnswerValidator` |
| `rag.execution` | `RagExecution`, `StageExecution` |
| `rag.evaluation` | `Evaluator`, `EvaluationResult`, `EvaluationFinding`, `EvaluationDimension` |
| `rag.indexing` | `IndexingPipeline`, `IndexableContent`, `IndexingReport` (no implementation) |
| `rag.pipeline` | `RagPipeline`, `StagedRagPipeline` |

`RagCoreBoundaryTest` fails the build if any core source imports Spring,
`jakarta.*`, Hibernate, `java.sql`, `java.net.http`, Jackson, or any
`org.signalengine` package other than `org.signalengine.rag`.

### 2. Seven stage contracts; two required, five optional

`Retriever` and `ContextAssembler` are required. `QueryProcessor`, `Reranker`,
`ContextRefiner`, `Generator`, `AnswerValidator` are optional; an absent stage
is skipped and changes nothing else. `QueryProcessor`, `Reranker`,
`ContextRefiner` and `AnswerValidator` each carry an `andThen` default so a new
transformation wraps the existing stage rather than editing another.

Each stage contract has a `default ComponentDescriptor descriptor()` — the whole
config/versioning surface, no framework.

### 3. `Context` is a first-class output, not a prompt

`RagPipeline` has two entry points: `execute(Query) → RagExecution` and
`assembleContext(Query) → Context`. The second runs query processing, retrieval,
reranking, assembly and refinement and stops — a configured `Generator` is never
invoked. `Context` preserves selected passages, their order, their `Provenance`
and their identifiers; it is not a formatted string.

### 4. Provenance is mandatory and generic

`Provenance` (source id mandatory; original URL, title, document id, passage id
optional; open attribute map) is attached to every `RetrievedPassage`, carried
unchanged into every `ContextPassage`, and referenced by every `Citation`. It
has no dependency on Signal Engine's `Source` and no fields beyond what a
citation needs.

### 5. Evaluation consumes a finished run and cannot touch the pipeline

`Evaluator.evaluate(RagExecution) → EvaluationResult`. The evaluator gets an
immutable record and no pipeline/stage reference. No runtime package depends on
`rag.evaluation`. No metric, scale, or LLM judge is defined —
`EvaluationFinding` is a dimension plus a free-text observation.

### 6. Indexing is a boundary only

`IndexingPipeline.index(IndexableContent) → IndexingReport`, with no
implementation. Parsing, chunking, metadata enrichment, embedding and
persistence are internal to a later implementation. `rag.pipeline` does not
depend on `rag.indexing`.

### 7. The two selected libraries are recorded, not integrated

`SlaouiSS/semantic-chunker` will sit inside a future `IndexingPipeline`
implementation at the chunking step; `SlaouiSS/spring-ai-hybrid-retriever` will
sit behind a future infrastructure `Retriever`. Neither is added to
`libs.versions.toml` (`CLAUDE.md` Section 23 — no dependency added merely to
document it).

### 8. Development LLM strategy is documented

A strong model via NVIDIA Build (free deployer) behind the `Generator` contract
for dev validation; the same contracts then run against local Ollama. No NVIDIA
or Ollama integration in this task.

## Consequences

- New package tree `org.signalengine.rag` (10 packages, 28 types + 10
  `package-info` files). No change to `domain`, `application`, `infrastructure`,
  `interfaces`, any migration, or any existing test.
- 16 fast-lane unit tests (`backend/src/test/java/org/signalengine/rag`) covering
  minimal composition, optional generator, optional-stage insertion,
  context-without-generation, provenance flow, contract-only wiring, evaluation
  independence, and the no-Signal-Engine-dependency boundary. No Spring, no
  database, no LLM, deterministic doubles only.
- Phases 7–8 implement these contracts in the infrastructure layer; the Task 6A
  capability path is available to a future `Generator`.
- No ADR supersedes; `docs/07-rag.md` gains Section 20 and its Summary is
  renumbered to Section 21.

## Trade-offs

- **Ten packages / 28 types for a contracts-only task.** Accepted: this is a
  reusable library boundary, every interface is a named stage of the conceptual
  pipeline (`CLAUDE.md` Section 15), and every record is a distinct carrier. The
  types are small and carry no logic.
- **`ContextRefiner` and `AnswerValidator` exist before anything uses them.**
  Accepted: the task explicitly requires the contracts to allow context
  compression/diversification and grounding/citation validation to be added
  "without rewriting the other stages"; without a post-assembly seam and a
  post-generation seam, adding those later forces edits to `ContextAssembler`
  and `Generator`. They are genuine replaceable capabilities, not speculative
  abstraction.
- **`ContextPassage` is nearly a copy of `RetrievedPassage`.** Accepted: a
  context passage has no meaningful retrieval score and its text may be
  compressed; a distinct type states "selected, ordered, traceable content — not
  a prompt".
- **A single peer package rather than a Gradle module.** Accepted: the boundary
  is enforced by test today and extraction stays a zero-code-change move.
- **`RagExecution` carries wall-clock timestamps.** Minor: useful to an
  evaluator, injected via `Clock` so tests are deterministic.

## Explicitly still open (untouched)

Embedding model / vector dimension (T3), pgvector index and distance function
(T7), chunking approach and parameters, retrieval parameters (top-k, cutoff),
reranking implementation, query transformation, context budget, evaluation
metrics, LLM judge, multilingual behaviour, Q12/T15, Q14, Q21, and every other
item in `docs/07-rag.md` Section 18. Task 8.1 resolves none of them, implements
no retrieval / embedding / chunking / generation / evaluation / indexing / API /
frontend, and adds no dependency.
