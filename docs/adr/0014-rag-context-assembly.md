# ADR 0014 — RAG Context Assembly

Status: Accepted
Date: 2026-09-07
Phase: 2 (docs/11-roadmap.md), Task 8.4 — the first real `ContextAssembler`

---

## Context

Task 8.1 fixed the RAG contracts, 8.2 added chunking, 8.3A added the embedding
contract, 8.3B persisted embedded passages, and 8.3C added the first real
`Retriever` (`PgVectorRetriever`, exact pgvector cosine Top-K). Task 8.4 adds the
stage after retrieval:

```
RetrievalResult → ContextAssembler → Context
```

The `Context` must be a generic, reusable output that a later LLM generator,
another agent, or another project can consume without importing Spring,
PostgreSQL, pgvector, an HTTP client, or any Signal Engine business type.

Constraints (from the task and `CLAUDE.md`): the generic RAG core
(`org.signalengine.rag.**`) stays framework-, persistence- and business-free;
context assembly belongs entirely to that core; no LLM generation, no answer
synthesis, no reranking, no hybrid retrieval, no query rewriting/expansion, no
summarisation or compression, no evaluation execution, no ANN index.

## Decisions

### 1. `Context` stays a first-class structured object

`Context` remains an ordered `List<ContextPassage>` plus a generic
`Map<String,String>` of assembly metadata — **not** a single formatted string. A
caller works with structured passages; a future `Generator` formats them into a
prompt however it needs. `Context` and `ContextPassage` reference only `java.*`
and `org.signalengine.rag.*` types (a reflection test asserts this), so they know
nothing about any model, prompt format, provider or Signal Engine concept.

The existing `Context` / `ContextPassage` contracts were **not changed**. The
retrieval score and the 0-based retrieval position are carried into each
`ContextPassage`'s open `metadata` (`retrievalScore`, `retrievalRank`) rather
than promoted to fields: after assembly the score is provenance-of-retrieval, not
a ranking signal, which is exactly what `ContextPassage`'s existing javadoc
already says. Adding a field would have been a contract change with no caller that
needs it.

### 2. `ContextAssembler` is a stage separate from `Retriever`

Retrieval answers "which passages match this query, and in what order". Assembly
answers "which of those passages, as coherent bounded evidence, go into the
context". These are different responsibilities with different reasons to change
(a new index vs. a new budget policy), so they stay separate contracts and
separate pipeline stages. The assembler never re-queries, never changes a
retrieval score, and never reorders by its own criteria.

### 3. The supplied implementation: `BudgetedContextAssembler`

A single deterministic implementation in the RAG core (like
`StructureAwareChunker` in Task 8.2 — the core ships the generic baseline; a
provider-specific one, if ever needed, is an infrastructure adapter). It does, in
order: exact-duplicate removal → order-preserving selection → character
budgeting. It never calls an LLM, touches a database, or reads a Signal Engine
type.

### 4. Retrieval order is preserved

The assembler introduces **no ranking of its own** — not by score, source,
recency, area, or any business attribute. It relies on the deterministic order
the `Retriever` already guarantees (Task 8.3C: `ORDER BY cosine_distance ASC,
p.id ASC`). Introducing a second ranking here would duplicate retrieval's job,
add a place for business logic to leak in, and make the pipeline harder to
reason about. The policy is recorded in the context metadata
(`orderingPolicy = retrieval-order`).

### 5. Exact-duplicate passage ids only

If the same `passageId` appears more than once, the first occurrence is kept and
the rest dropped; the count is reported. **No** semantic or near-duplicate
detection: that is Task 6B's mechanism, built for a different purpose (grouping
Raw Information Items), and it needs an LLM or an embedding comparison — neither
belongs in the generic assembler. Two passages with identical text but different
ids are both kept. Deferring semantic collapsing keeps this stage simple,
deterministic and dependency-free.

### 6. The context budget is a validated character budget

`ContextBudget` is a value object: `DEFAULT_MAX_CHARACTERS = 12000` (provisional),
`MAX_MAX_CHARACTERS = 200000` (hard ceiling), constructor validation. The unit is
**characters**, deliberately:

- the RAG core carries no tokenizer, and adding a heavyweight one for this task
  is not justified;
- a character count is exact and fully deterministic — a fake "precise" token
  count would not be;
- it is **model-independent** — it is not an Ollama / NVIDIA / OpenAI context
  window and is not derived from any model's prompt limit. A future generator
  that renders the context for a specific model applies its own tighter,
  model-specific limit on top.

The metadata exposes a rough `estimatedTokens` (≈ characters / 4) as a
convenience only; the budget is never enforced in it. On the Java side the budget
is bound from `signal-engine.rag.context.max-context-characters`.

### 7. Whole passages are preserved; selection is first-fit in order

Passages are considered in retrieval order; one is included if the running total
stays within budget, otherwise it is skipped and the next is still considered
(first-fit), keeping as many whole passages as possible. A passage is **never
split, truncated, or summarised** to use remaining space. A passage whose own
text exceeds the entire budget is excluded and reported (`skippedOversized`); if
that leaves nothing selected, the `Context` is legitimately empty — the same
already-supported state as an empty retrieval result — and the metadata explains
why. This "exclude and report, never corrupt" behaviour is the smallest one
consistent with the existing `Context` contract (which already permits an empty
context).

### 8. No LLM compression, and `ContextRefiner` stays optional and unimplemented

Shrinking passage text to fit more evidence (summarisation, LLM compression,
near-redundancy removal, diversification) carries its own quality and grounding
risks and is a separate concern. The RAG core already models it as
`ContextRefiner` — an **optional, composable** stage that runs *after* assembly,
so a new refinement is added without touching the assembler. Task 8.4 implements
**no** `ContextRefiner`: the seam exists, every pipeline may omit it, and
`BudgetedContextAssembler` never depends on it. If the context is too large, the
answer is "fewer complete passages", not "compressed passages".

### 9. Minimal pipeline integration

`StagedRagPipeline` already runs `retriever → contextAssembler (→ contextRefiner?
→ generator?)` and needed **no change**. Task 8.4 wires the real
`BudgetedContextAssembler` as a bean and composes a minimal query-time
`RagPipeline` bean (`PgVectorRetriever` + `BudgetedContextAssembler`, no
generator), so `RagPipeline.assembleContext(Query)` returns a real assembled
`Context` and `RagPipeline.execute(Query)` returns a `RagExecution` with
retrieval and context but no answer. Nothing in the backend consumes that bean
yet — there is no query API.

### 10. Reusable outside Signal Engine

`ContextAssembler`, `Context`, `ContextPassage`, `ContextBudget` and
`BudgetedContextAssembler` are all in the framework-free RAG core. Another
project supplies its own `Retriever`, obtains `RetrievedPassage`s, and calls the
assembler — with no dependency on Spring, PostgreSQL, pgvector, an HTTP client or
any Signal Engine type. `RagCoreBoundaryTest` enforces the boundary.

## Consequences

- RAG core: `rag.context` gains `ContextBudget` and `BudgetedContextAssembler`.
  `Context`, `ContextPassage`, `ContextAssembler`, `ContextRefiner` unchanged.
  `RagComponentType` unchanged (`CONTEXT_ASSEMBLER` already existed).
- Infrastructure: `org.signalengine.infrastructure.rag.context` —
  `RagContextProperties` (`signal-engine.rag.context.max-context-characters`),
  `RagContextConfiguration` (`ContextAssembler` bean + minimal `RagPipeline`
  bean). No PostgreSQL, HTTP or provider code.
- Tests: `ContextBudgetTest` (4), `BudgetedContextAssemblerTest` (18),
  `ContextAssemblyPipelineTest` (2), `ContextAssemblyRetrievalIntegrationTest`
  (5, real PostgreSQL + pgvector). Unit total 267 → 291; integration 74 → 79.
- No migration, no new dependency.
- `docs/07-rag.md` gains Section 25 (Summary → 26); `docs/03-technical-spec.md`
  Section 11.3 and `docs/11-roadmap.md` Phase 2 progress updated.

## Trade-offs

- **Character budget, not tokens.** Less precise for a specific model, but exact,
  deterministic, model-independent and dependency-free. A generator applies the
  real model limit; a `ContextRefiner` or a tokenizer-backed budget can be added
  later without changing the `ContextAssembler` contract.
- **First-fit past an over-budget passage**, rather than stopping at the first
  one that does not fit. Keeps more whole evidence and gives sensible behaviour
  when the top passage is oversized, at the cost of the context not always being
  a strict ranked prefix. Fully deterministic and reported in metadata.
- **Retrieval score kept in metadata, not a field.** No contract change; honest
  about what the value means after assembly. A caller that wants it reads one
  metadata key.
- **A `RagPipeline` bean with no generator.** Might look incomplete, but
  `assembleContext` is a first-class, documented output and the bean makes the
  end-to-end path real and testable. Adding a generator later changes only this
  configuration.
- **Exact-duplicate ids only.** A retrieval result today cannot contain a
  duplicate id (the store keys on passage id), so the guard is defensive. It
  costs almost nothing and protects a future hybrid/fused retriever that merges
  candidate lists.

## Explicitly still open (untouched)

Context **compression / summarisation** and any real **`ContextRefiner`**;
**generation / answer synthesis** (the next stage); **reranking**, **hybrid
retrieval**, **query transformation** (§ ADR 0013); **token-accurate budgeting**
(needs a tokenizer the RAG core deliberately omits); semantic / near-duplicate
passage collapsing in the context; per-source or per-document caps and diversity
constraints; **T3** (embedding model / dimension), **T7** (ANN index); the
evaluation subsystem; multilingual behaviour. Task 8.4 implements no generation,
compression, reranking, hybrid retrieval, query rewriting, evaluation execution,
frontend, search API or scheduler change.
