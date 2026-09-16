# ADR 0015 — RAG Grounded Generator

Status: Accepted
Date: 2026-09-07
Phase: 2 (docs/11-roadmap.md), Task 8.5 — the first real `Generator`

---

## Context

Task 8.1 fixed the RAG contracts, 8.2 added chunking, 8.3A/B/C added embedding,
persistence and the first real `Retriever`, and 8.4 added the first real
`ContextAssembler`. Task 8.5 adds the stage after context assembly:

```
Query + Context → Generator → RagAnswer (grounded, with citations, or "insufficient")
```

This implements `docs/07-rag.md` §10 / `docs/06-ai-agents.md` §4.7 (Grounded
Question Answering). The `RagAnswer` must be a generic, reusable output; the RAG
core must stay framework-, persistence-, provider- and business-free.

Constraints (task + `CLAUDE.md`): reuse the Task 6A structured-AI-capability
infrastructure (no second LLM provider architecture, no direct Ollama/NVIDIA call,
no provider code in the RAG core); structured JSON output; structural citation
validation with one bounded repair then a typed failure; an explicit
insufficient-evidence state (never a forced answer); retrieved text treated as
untrusted; no reranking, hybrid retrieval, query transformation, context
compression, `ContextRefiner`, evaluation execution, or LLM judge.

## Decisions

### 1. The generator is a separate, optional RAG stage

Retrieval decides *which* passages; assembly decides *which of them, bounded*;
generation decides *what to say, grounded in them*. Different responsibilities,
different reasons to change (a new index / a new budget / a new model or prompt),
so they stay separate contracts and separate pipeline stages. The core
`Generator` contract (`generate(Query, Context) → RagAnswer`) was sufficient and
unchanged.

### 2. It consumes `Context`, it does not retrieve

The generator receives the assembled `Context` and nothing else it could retrieve
with. It never re-queries, never re-ranks, never modifies the context, never
summarises it before answering. Retrieval is Java-owned and upstream
(`docs/03-technical-spec.md` §9.8; `docs/06-ai-agents.md` §4.7 item 3). This keeps
the Python capability stateless and storage-free and keeps grounding auditable —
the exact evidence the answer must rest on is fixed before generation starts.

### 3. A thin infrastructure adapter over the Task 6A path

`AiCapabilityAnswerGenerator` (infrastructure) implements the core `Generator` and
calls a new **`answer`** Python capability (contract v1) through the existing
`AiCapabilityInvoker` — exactly the pattern of `AiCapabilityEmbeddingModel` (8.3A)
and `SemanticChunkerAdapter` (8.2). No `com.*`/provider SDK, no HTTP client, no
PostgreSQL, no Jackson in this class; the transport and serialisation are the
Task 6A invoker's. The RAG core `generation` package still names no LLM.

### 4. Structured output; the model returns passage ids only

The `answer` capability returns validated JSON
`{"answered": bool, "answer": str, "citations": [{"passageId": str}]}`. The model
never returns provenance or a citation string — only ids that appear in the
supplied context block. Java attaches each `Citation`'s `Provenance` from the
matching `ContextPassage`, so a citation can never carry a fabricated source and
is never parsed out of free text. `RagAnswer` / `Citation` (8.1) were sufficient;
no contract change was needed for citations.

### 5. Structural citation validation, in layers, independent of the generator

- The Python capability's bounded-repair `extra_check`: every cited id was
  supplied; answered ⇒ ≥1 citation; insufficient ⇒ 0 citations; no id twice.
- The Java adapter re-checks the same rules and never trusts the model.
- `GroundingAnswerValidator` (RAG core, the pipeline's `AnswerValidator`) is a
  deterministic structural pass that works for **any** `Generator`: it drops a
  citation that does not resolve to a supplied passage or whose provenance does
  not match, collapses duplicates, and downgrades an answered response with no
  valid support to insufficient evidence, recording every correction in metadata.

Three layers because grounding is the most important requirement and must not
depend on one implementation being correct. The validator lives in the core (like
`BudgetedContextAssembler` and `StructureAwareChunker`) because it is pure
structural logic over core types.

### 6. Unsupported citations are rejected, not silently accepted

A citation to a passage that is not in the context is: repaired once (Python),
then a `GenerationException` (Java adapter) — and, defensively, dropped by the
validator if it ever reaches it. The missing passage is never invented. "Reject
and observe" over "accept and hope".

### 7. Insufficient context must not produce a forced answer

An empty context short-circuits to `answered = false` with no LLM call. Otherwise
the prompt requires `answered = false` + no citation when the evidence does not
support an answer, and every layer enforces that an insufficient answer carries no
citations. `RagAnswer.answered` already models this explicitly. Forcing an answer
from the model's own knowledge is exactly the hallucination this task exists to
prevent.

### 8. Retrieved text is untrusted

A passage may contain "ignore previous instructions" style text. The system
prompt separates INSTRUCTIONS from the CONTEXT block and states that the passages
are data, never instructions, and that such text must never be followed. This is
a prompt-level control plus a test with a deterministic path; it is not a general
security framework, and it is not claimed to be a guarantee — but the structural
grounding (a cited passage must exist; an answered verdict needs a citation)
holds regardless of what the passage text says.

### 9. Provider independence is preserved

One `Generator` class, not one per provider. The model, provider, prompt and
timeout are the Python service's configuration; the same class works with Ollama,
NVIDIA Build or another compatible provider with no RAG-core change. Only the
capability contract version is bindable on the Java side.

### 10. `GenerationException` — the one RAG-core addition

A provider outage, a timeout, or output still invalid after the one repair is a
typed `AiCapabilityOutcome.Failed`; the adapter turns it into an unchecked
`GenerationException` (consistent with `EmbeddingException` / `IndexingException`)
— distinct from the insufficient-evidence outcome. `StagedRagPipeline.execute`
propagates it; a query API maps it to "answering temporarily unavailable"
(`docs/06-ai-agents.md` §4.7 item 9). No richer failure type, no retry, no
`RagExecution`-with-a-failed-answer state — those would be a pipeline redesign the
task forbids.

### 11. Evaluation stays separate

No LLM judge, no faithfulness / factuality / answer-quality scoring, no evaluation
database, no async evaluation agent. Generation validation here is **structural**.
Whether the answer text is semantically entailed by the cited passages is the
`Evaluator` contract's concern — a later, asynchronous task
(`docs/09-evaluation.md`). Nothing in the runtime pipeline depends on evaluation.

### 12. No reranking / hybrid retrieval / context refinement here

Out of scope by the task. Each remains a documented deferral behind its existing
contract (`Reranker`, a hybrid `Retriever`, `ContextRefiner`); adding any of them
later changes only that stage's wiring, not the generator.

## Consequences

- RAG core: `rag.generation` gains `GenerationException` and
  `GroundingAnswerValidator`. `Generator`, `RagAnswer`, `AnswerValidator`,
  `Citation` unchanged. `RagComponentType` unchanged (`GENERATOR`,
  `ANSWER_VALIDATOR` already existed). `StagedRagPipeline.execute` now records
  `durationMillis` / `answered` on the generation and validation stage notes — no
  other change; `assembleContext` untouched.
- Infrastructure: `org.signalengine.infrastructure.rag.generation` —
  `AiCapabilityAnswerGenerator`, `RagGenerationProperties`
  (`signal-engine.rag.generation.contract-version`), `RagGenerationConfiguration`
  (`Generator` + `AnswerValidator` beans). `RagContextConfiguration.ragPipeline`
  now picks up the optional `Generator` / `AnswerValidator` via `ObjectProvider`.
- Python: `answer` capability + `answer/v1` prompt asset; registered in
  `main.py`; contract fixtures `request.answer.json` / `response.answer-success.json`;
  `test_contract.py` extended. No envelope-schema change (capability is a free
  string). No new env var — reuses `AGENTS_LLM_PROVIDER` / `OLLAMA_MODEL` /
  `AGENTS_LLM_REQUEST_TIMEOUT_SECONDS`.
- Tests: core `GroundingAnswerValidatorTest` (14), `GenerationExceptionTest` (2),
  `GenerationPipelineTest` (7); infra `AiCapabilityAnswerGeneratorTest` (14),
  `AnswerCapabilityContractTest` (2); integration
  `GroundedGenerationRetrievalIntegrationTest` (2, real PostgreSQL + pgvector,
  deterministic in-test capability); opt-in `AnswerGeneratorRealModelManualTest`
  (3, gated on `RAG_ANSWER_REALMODEL`). Python `test_answer_capability.py` (11).
- No migration, no new dependency.
- `docs/07-rag.md` §26 (Summary → §27); `docs/03-technical-spec.md` §11.3;
  `docs/11-roadmap.md` Phase 2 progress; README updated.

## Trade-offs

- **Structural grounding only.** It guarantees citations point at real supplied
  passages and that an answered verdict has one — not that the prose is faithful.
  Semantic faithfulness is deferred to evaluation. Documented plainly rather than
  overclaimed.
- **A thrown `GenerationException` rather than a failure value.** Simpler, matches
  the other RAG-core stage exceptions, and avoids a `RagExecution` "generation
  failed" state. The caller catches it at the API boundary.
- **Answer-level citations, not per-claim.** `RagAnswer.citations` is a flat list;
  the v1 capability returns ids only. Per-claim spans (`Citation.quotedText`) are
  a later refinement, no contract change needed.
- **Three validation layers.** Some redundancy (Python checks, Java re-checks,
  validator re-checks). Deliberate: grounding is the point, and the validator
  must also protect against a *different* future generator.
- **`ObjectProvider` for the optional generator in the pipeline bean.** Slightly
  less explicit than a second config owning the pipeline, but it keeps the
  "generator is optional" nature real at the wiring level and needs no change to
  the 8.4 config's structure.
- **Real-model validation ran on a slow local `qwen3:14b` (CPU).** One call ~60s;
  the opt-in test is not part of any automated lane. It confirmed behaviour, not
  latency.

## Explicitly still open (untouched)

Semantic factuality / faithfulness evaluation, LLM judge, answer-quality metrics,
a golden-answer regression suite, an evaluation store (all the `Evaluator`
concern); per-claim citations / supporting spans; multi-turn conversation;
a larger real-world generation benchmark; `ContextRefiner` / context compression;
reranking; hybrid retrieval; query transformation (rewriting, expansion, HyDE);
ANN index (T7); T3 (embedding model / dimension); multilingual behaviour. Task 8.5
implements no reranking, hybrid retrieval, query transformation, context
compression, evaluation execution, frontend, search API, or scheduler change, and
does not proceed to any later RAG stage.
