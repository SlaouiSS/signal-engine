# ADR 0016 — RAG Evaluation

Status: Accepted
Date: 2026-09-08
Phase: 2 (docs/11-roadmap.md), Task 8.6 — the independent RAG evaluator

---

## Context

Task 8.1 reserved evaluation as a subsystem **outside** the runtime RAG pipeline:
`Evaluator.evaluate(RagExecution) → EvaluationResult`, with `EvaluationFinding`
and `EvaluationDimension`, and **no metric, no scale, no LLM judge** fixed
(`docs/07-rag.md` §20.4; `docs/09-evaluation.md` §11, §21). Tasks 8.3C–8.5 made
the runtime path real (retrieval → context assembly → generation → grounding
validation) and each produces data on a `RagExecution`.

Task 8.6 implements the evaluator: deterministic, after-the-fact, framework-free,
independent of retrieval/generation execution, persistence, scheduling,
orchestration, model providers and Signal Engine business logic. No microservice,
no evaluation database, no change to runtime pipeline behaviour.

## Decisions

### 1. One deterministic evaluator over the existing execution data

`RagExecutionEvaluator implements Evaluator` lives in the generic core package
`org.signalengine.rag.evaluation`. It consumes a finished, immutable
`RagExecution` plus a hand-authored `RagEvaluationDataset`, computes numbers from
data already on the execution, and returns an `EvaluationResult`. It re-runs
nothing, reaches no store, calls no model. `evaluate(x)` twice yields an equal
result. `RagCoreBoundaryTest` confirms it imports only `java.*` and
`org.signalengine.rag.*` (no Spring, JDBC, pgvector, HTTP, Jackson, provider or
business type); `EvaluationIndependenceTest` confirms the evaluation package
still imports no pipeline, `Retriever`, `Generator` or `QueryProcessor`.

### 2. Minimal contract changes

- **`EvaluationMetric`** (new record) — the task requires metrics with a stable
  name and a numeric value; `EvaluationFinding` (free text only) cannot carry
  that cleanly, and stuffing a parsed-from-string number into its `details` map
  would be a worse contract. `EvaluationMetric(dimension, name, value, details)`
  is flat and has no verdict or weighting.
- **`EvaluationResult`** — one additive field, `List<EvaluationMetric> metrics`,
  parallel to `findings`, plus a `metricValue(name)` accessor. The one existing
  constructor call (a test double) was updated.
- **`EvaluationDimension`** — one added value, `EXECUTION_OUTCOME`, for scope
  item D ("record findings when the execution cannot be meaningfully
  evaluated"). Additive; no exhaustive switch exists on the enum.
- **`Evaluator`, `EvaluationFinding`** — unchanged.

No "overall RAG score" type or field: the task forbids one unless the
architecture requires it, and it does not.

### 3. Ground truth comes from a hand-authored dataset, not the execution

Retrieval and answerability metrics need per-query ground truth that a single
`RagExecution` does not carry. The evaluator is constructed with a
`RagEvaluationDataset` and looks the current execution's `originalQuery().text()`
up in it (exact match). This keeps `evaluate(RagExecution)` — the reserved
contract — unchanged. A query with no judgement produces an `EXECUTION_OUTCOME`
note and no ranking metrics (scope item D), not a guess.

`RagEvaluationDataset` / `RelevanceJudgement` are **shape only** — records with a
`version` string; they read no file and parse no format, so the core stays
framework-free. The tests build one from a versioned JSON fixture
(`src/test/resources/rag/evaluation/retrieval-eval-v1.json`) through a **test-only**
Jackson loader. Passage ids in the fixture are synthetic; a dataset is never
production data (scope requirement).

### 4. Deterministic metrics only, same definitions as the benchmark

- **Retrieval** (when judged): `recall@1/3/5/10`, `reciprocalRank`, `ndcg@10` of
  the retrieved ranking against the judged relevant passages. Same definitions as
  the Task 8.3A/8.3C retrieval benchmark (`metrics.py` and its Java counterpart),
  so an evaluator number is comparable to a benchmark number. Corpus MRR / mean
  recall@k / mean nDCG = the plain mean across results (`RagEvaluationSummary`).
- **Answerability** (when judged, and an answer was generated):
  `answerabilityAgreement` — 1.0 when `answer.answered()` equals the dataset's
  `answerable`, else 0.0.
- **Grounding / citation validity** (always, structural): `citationValidity` =
  resolved citations / total, where "resolved" means the `Citation`'s
  `passageId` is a passage in the execution's `Context` and its `Provenance`
  matches. Reuses the existing `Citation` and context model — no second citation
  representation. Findings for unresolved / provenance-mismatched citations and
  for an answered response with no citation.

No proprietary scoring formula, no complex ranking metric, no LLM-derived score.

### 5. `RetrievalMetrics` is a small package-private helper in the core

The metric math (recall@k, reciprocal rank, MRR, nDCG@k) is a focused
package-private class next to the evaluator that uses it. It duplicates ~40 lines
of well-understood pure math that the Task 8.3C benchmark keeps its own
**test-only** copy of; the copies are in different layers (core evaluation vs.
a `@Tag("benchmark")` test), and merging them would mean editing the 8.3C
benchmark — out of scope for this task. Noted as a known minor duplication.

### 6. No God class

`RagExecutionEvaluator` orchestrates; the metric math is `RetrievalMetrics`;
corpus aggregation is `RagEvaluationSummary`. Citation and answerability checks
are short private methods on the evaluator (extracting them would not improve
clarity). No `EvaluationService`.

### 7. No LLM judge — and why the deferral is a documented decision

An LLM-as-a-judge for semantic faithfulness would require an unresolved
architectural decision: which capability, which prompt, how its own
non-determinism is handled in an "evaluation" result, and how it is validated.
Per the task, this is documented as an **open decision** rather than implemented
speculatively (`docs/09-evaluation.md` §21).

### 8. No integration test

The evaluator is pure deterministic logic over immutable data types — no
database, no LLM, no Spring context. It is fully unit-testable. An integration
test would add cost without coverage.

## Consequences

- RAG core: `org.signalengine.rag.evaluation` gains `EvaluationMetric`,
  `RelevanceJudgement`, `RagEvaluationDataset`, `RagExecutionEvaluator`,
  `RagEvaluationSummary`, `RetrievalMetrics` (package-private).
  `EvaluationResult` +1 field; `EvaluationDimension` +1 value; `package-info`
  updated. `RagCoreDoubles.RecordingEvaluator` constructor call updated (1 line).
- Tests: `RetrievalMetricsTest` (8), `EvaluationModelTest` (4),
  `RagEvaluationDatasetTest` (4), `RagEvaluationSummaryTest` (3),
  `RagExecutionEvaluatorTest` (14); test helpers `RagEvaluationDatasets`
  (JSON loader) and `RagExecutionFixtures`; fixture
  `src/test/resources/rag/evaluation/retrieval-eval-v1.json`.
- No migration, no new dependency, no Python change, no runtime pipeline change.
- `docs/07-rag.md` §27 (Summary → §28); `docs/09-evaluation.md` §11 updated;
  README updated.

## Trade-offs

- **A new `EvaluationMetric` type + an `EvaluationResult` field** rather than
  reusing `EvaluationFinding.details`. Justified: the task requires structured
  numeric metrics; a stringly-typed number in a details map is a worse contract.
  Kept additive and minimal.
- **Per-execution metrics; corpus numbers via a separate helper.** The
  `Evaluator` contract is single-execution. `reciprocalRank` per query is the
  MRR term; `RagEvaluationSummary.mean` produces the corpus MRR. Clean and
  matches the contract.
- **Exact query-text match into the dataset.** Simple and deterministic;
  normalisation / fuzzy matching is an open decision.
- **Structural grounding check duplicates part of `GroundingAnswerValidator`'s
  logic.** Deliberate: the validator *corrects* at runtime, the evaluator
  *observes* after the fact, and the evaluator must work on any `RagExecution`
  including one from a pipeline without the validator.
- **`RetrievalMetrics` duplicated with the 8.3C test helper.** ~40 lines of pure
  math, different layers; merging would touch out-of-scope code.

## Open decisions

- **Canonical evaluation dataset format and storage** — the core defines only the
  in-memory shape; whether a JSON fixture, a database table, or a shared golden
  set is canonical, and how it is versioned and loaded in production, is
  undecided.
- **LLM-as-a-judge for semantic faithfulness / factuality** — approach,
  capability, prompt, and how judge non-determinism is represented in a
  result: undecided.
- **Answer relevance / quality and summary quality scoring** — undecided
  (semantic).
- **Automatic pass/fail thresholds and regression gates** — the evaluator
  reports numbers; what counts as acceptable, and where a gate lives, is
  undecided.
- **Production evaluation scheduling and persistence** — undecided; an
  `EvaluationResult` carries what a later persistence task needs.
- **Multilingual evaluation** — deferred with the rest of multilingual support
  (`docs/02-functional-spec.md` Q24).
- **Query-text matching into the dataset** (exact vs. normalised vs. by query
  id) — currently exact; open.

## Explicitly not implemented (out of scope for Task 8.6)

Reranking, query transformation, context refinement, multilingual RAG, an LLM
judge, an evaluation service/microservice, an evaluation database, scheduling,
dashboards, production monitoring, an improvement loop, a combined RAG score, and
any change to retrieval/generation pipeline behaviour.
