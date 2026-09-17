# Signal Engine — Evaluation Strategy

Document ID: `09-evaluation.md`
Status: Accepted — reviewed as part of the Phase 0 documentation baseline
(`docs/11-roadmap.md` Section 3); decisions this document marks open or
provisional remain open or provisional until resolved.
Depends on: `CLAUDE.md`, `docs/01-product-spec.md`, `docs/02-functional-spec.md`,
`docs/03-technical-spec.md`, `docs/04-architecture.md`, `docs/05-data-model.md`,
`docs/06-ai-agents.md`, `docs/07-rag.md`, `docs/08-ingestion.md`

---

## 1. Purpose and Scope

Evaluation exists to determine whether Signal Engine produces **useful,
relevant, grounded, and reliable results** — not to assume that quality
follows automatically from a chosen model, provider, or architecture
(`CLAUDE.md` Section 17). This document defines the evaluation **strategy**:
what should eventually be measured and how, at a conceptual level, without
building the dedicated AI evaluation subsystem that `docs/01-product-spec.md`
Section 8 explicitly defers beyond the MVP.

The evaluation scope covers the AI capabilities defined in
`docs/06-ai-agents.md` Section 4:

- Near-Duplicate Assessment
- Classification
- Relevance Assessment
- Importance Assessment
- Summarization
- Embedding Generation
- Grounded Question Answering

It also covers deterministic system behavior where correctness can be checked
objectively (Section 4):

- exact deduplication
- provenance preservation
- processing correctness
- idempotency
- source collection correctness
- retrieval correctness

This document does not redefine any capability — it references
`docs/06-ai-agents.md`, `docs/07-rag.md`, and `docs/08-ingestion.md` rather
than restating them. It does not design an evaluation platform, select an
evaluation framework, create a dataset, or resolve any open product,
functional, or technical question (Section 21).

---

## 2. Evaluation Principles

Consistent with `CLAUDE.md` and `docs/01-08`; no additional principle is
invented beyond this list.

- **Evidence-based.** A claim about quality must be backed by an observable
  result, not by assumption (`CLAUDE.md` Section 17).
- **AI quality is not assumed from model/provider choice.** Choosing a
  capable provider does not substitute for measuring what that provider
  actually produces for Signal Engine's specific tasks.
- **Source grounding must be evaluated**, not only enforced structurally —
  structural enforcement (`docs/06-ai-agents.md` Section 8) reduces the risk
  of fabrication; evaluation is what confirms it is working.
- **False positives and false negatives both matter.** An item wrongly
  surfaced and an important item wrongly missed are both quality failures,
  and evaluation must consider both, not just one direction
  (Sections 7–8).
- **Business decisions and AI assessments remain distinguishable.** An AI
  assessment (e.g. "important enough") is evaluated as an assessment; the
  Java-owned decision that acts on it (`docs/06-ai-agents.md` Section 6) is
  evaluated separately, as system behavior.
- **Reproducible where possible.** Given the same input and the same model
  configuration, evaluation results should be comparable across runs.
- **Independent from the AI implementation.** Evaluation examines a
  capability's observable behavior against its contract, not its internal
  implementation (Section 16).
- **Supports provider/model replacement.** Evaluation concepts must not be
  tied to a specific provider or model (Section 16).
- **Not part of the runtime business workflow.** Evaluation does not gate,
  block, or alter a live processing decision — it is a separate,
  after-the-fact activity (`docs/03-technical-spec.md` Section 16.3).
- **Must not require production architecture to become complex.** Evaluation
  is designed to fit the existing MVP architecture, not to justify new
  infrastructure (Section 18; `docs/03-technical-spec.md` Section 25).

---

## 3. What Needs to Be Evaluated

| Dimension | What is evaluated |
|---|---|
| Collection | Was expected source information collected correctly? |
| Normalization | Is useful content preserved and consistently represented? |
| Exact deduplication | Are true duplicates avoided? |
| Near-duplicate detection | Are semantically duplicate stories identified appropriately? |
| Classification | Are areas assigned correctly? |
| Relevance | Is information correctly judged relevant/not relevant? |
| Importance | Are important relevant items surfaced without excessive noise? |
| Summary | Is the summary accurate, concise, and source-grounded? |
| Provenance | Can output be traced to original source content? |
| Retrieval | Does search retrieve relevant stored information? |
| Q&A grounding | Are answers supported by retrieved passages? |
| Q&A correctness | Does the answer accurately reflect the supplied evidence? |
| Idempotency | Does repeated processing avoid unintended duplication? |
| Failure handling | Are failures visible and recoverable? |

No numerical target (a precision percentage, a recall threshold, or similar)
is defined for any dimension — those remain open, product-level decisions,
not something this document invents (`docs/01-product-spec.md` Section 12,
"measurable success targets").

---

## 4. Deterministic Evaluation

Some aspects of Signal Engine can be tested objectively, without an LLM
judge, because they are governed by deterministic rules
(`docs/03-technical-spec.md` Section 3.3):

- exact duplicate detection (`docs/08-ingestion.md` Section 8);
- identifier uniqueness (`docs/05-data-model.md` Section 17);
- provenance links (`docs/05-data-model.md` Section 15;
  `docs/08-ingestion.md` Section 13);
- source URL preservation;
- processing-state transitions (`docs/05-data-model.md` Section 14;
  `docs/08-ingestion.md` Section 14);
- idempotency (`docs/08-ingestion.md` Section 15);
- schema validation of AI requests/responses (`docs/03-technical-spec.md`
  Section 8.3);
- vector dimension validation (`docs/06-ai-agents.md` Section 4.6, item 7);
- citation references pointing to supplied passages
  (`docs/07-rag.md` Section 10);
- persistence consistency;
- retry behavior (`docs/03-technical-spec.md` Section 13);
- source connector behavior (`docs/08-ingestion.md` Section 4).

**"System correctness" is distinct from "semantic AI quality."** Deterministic
evaluation confirms the pipeline behaves as designed — states transition
correctly, duplicates are not created, citations resolve to real passages. It
does **not** and cannot confirm that a classification is the *right* area, that
a relevance judgement is the *right* judgement, or that a summary is
well-written — those are semantic questions and require the evaluation
approaches in Sections 5–12. Deterministic tests are a necessary but not
sufficient part of evaluating AI quality.

---

## 5. AI Capability Evaluation

| Capability | Main quality questions |
|---|---|
| Classification | Are the correct area(s) assigned, and is the information type reasonable? |
| Relevance | Is the relevant/not-relevant decision correct given the user's interests? |
| Importance | Does it surface genuinely important information, without excessive noise? |
| Summarization | Is the summary accurate, grounded, and concise? |
| Near-Duplicate | Is the same underlying story correctly identified as such? |
| Embeddings | Do embeddings produce useful semantic representation/retrieval behavior? |
| Grounded Q&A | Is the answer correct and fully supported by the supplied context? |

For each capability, conceptually:

- **Classification** — *expected behavior:* area(s) matching the item's
  actual subject matter; *likely failure modes:* missing an applicable area,
  assigning an unrelated area; *possible evaluation method:* comparing
  output against human-judged expected area(s) for representative items.
- **Relevance** — *expected behavior:* a relevant/not-relevant judgement that
  matches what a reasonable user, given their configured interests, would
  decide; *likely failure modes:* relevant items marked not relevant (false
  negative), noise marked relevant (false positive); *possible evaluation
  method:* human review of a representative sample against configured
  interests (Section 7).
- **Importance** — *expected behavior:* a signal is created only for
  information that genuinely warrants attention; *likely failure modes:*
  important information never surfaced, unimportant information surfacing
  too often; *possible evaluation method:* human review of signals produced
  versus relevant information that did not become a signal (Section 8).
- **Summarization** — *expected behavior:* concise, accurate, source-grounded
  text; *likely failure modes:* unsupported claims, omission of an important
  fact, verbosity; *possible evaluation method:* human review against the
  source content, and/or a structural check that claimed facts appear in the
  source (Section 9).
- **Near-Duplicate** — *expected behavior:* items reporting the same
  underlying story are grouped; distinct stories are not; *likely failure
  modes:* merging distinct stories, missing a true near-duplicate; *possible
  evaluation method:* human review of grouped vs. ungrouped pairs
  (Section 10).
- **Embeddings** — *expected behavior:* semantically similar content is
  retrieved together; *likely failure modes:* poor retrieval recall/precision,
  unrelated content ranked highly; *possible evaluation method:* inspecting
  retrieval results for representative queries (Section 11).
- **Grounded Q&A** — *expected behavior:* an answer that is correct and
  traceable to the supplied passages, or an honest "insufficient information"
  when it is not; *likely failure modes:* unsupported claims, invalid
  citations, missing an answer the passages actually support; *possible
  evaluation method:* human review comparing the answer to the supplied
  passages (Section 6, 11).

**No final benchmark dataset or scoring implementation is defined here** —
see Section 14.

---

## 6. Grounding Evaluation

This is the most important evaluation dimension, because source grounding is
a defining product property (`docs/01-product-spec.md` Section 3;
`docs/06-ai-agents.md` Section 8).

What eventually needs evaluating:

- **factual support** — does each claim in an output correspond to something
  the supplied source content or retrieved passages actually say?
- **citation validity** — does each citation reference a passage that was
  actually supplied to the capability (`docs/07-rag.md` Section 11)?
- **citation completeness**, where applicable — are the claims that need a
  citation actually cited?
- **unsupported claims** — statements not traceable to the supplied material;
- **invented facts** — content that contradicts or has no basis in the
  supplied material;
- **invented sources/citations** — a citation to a passage that was never
  supplied;
- **contradiction with source content** — an output that states something the
  source material does not support or actively contradicts.

**For Q&A:** every factual claim in a grounded answer should be traceable to
the supplied retrieval passages (`docs/06-ai-agents.md` Section 4.7;
`docs/07-rag.md` Section 10–11).

**For summaries:** claims should be supported by the underlying source
content (`docs/06-ai-agents.md` Section 4.5;
`docs/02-functional-spec.md` Section 8.2, R4).

**No specific automated grounding metric is chosen here.** Approaches such as
claim-by-claim comparison against source text are conceptually valid future
methods, but no framework, library, or scoring formula is selected or approved
by this document (Section 13).

---

## 7. Relevance Evaluation

Relevance is evaluated against the user's **explicit configured interests**
(`docs/02-functional-spec.md` Section 7.5), never against an implicit or
inferred profile. The fundamental question:

> **"Would this information reasonably be considered relevant to the
> configured interest?"**

Evaluation should account for both directions of error:

- **False positives** — information marked relevant that a reasonable user,
  given their configured interests, would not consider relevant.
- **False negatives** — information marked not relevant that a reasonable
  user would have wanted to see.

**No final relevance score or threshold is defined here.** Whether relevance
assessment captures a "degree" beyond the relevant/not-relevant boolean, and
how any such degree would be used, remains open
(`docs/06-ai-agents.md` Section 4.3, "explicitly open"), and evaluation
approaches must not presuppose an answer to it.

---

## 8. Importance / Signal Evaluation

Importance is evaluated **separately from relevance**
(`docs/06-ai-agents.md` Section 4.4). The evaluation question:

> **"Does the system surface information that is genuinely important enough
> to become a Signal?"**

Both directions matter:

- **Important information missed** — relevant information that should have
  become a Signal but did not.
- **Unimportant information surfaced** — a Signal created for information that
  does not warrant the user's attention, adding noise.

**This document does not invent the signal-selection criteria.** Evaluation
can only measure outcomes **against** whatever criteria are eventually
approved — it cannot supply those criteria itself, and doing so would
silently resolve a product decision this document must not touch. Explicitly
preserved:

- **Q4** — signal-selection criteria (`docs/02-functional-spec.md` Q4;
  `docs/06-ai-agents.md` Section 4.4, Section 14).
- **T16** — signal-decision guardrails, i.e. how the Java-owned state
  transition consumes the importance assessment
  (`docs/03-technical-spec.md` Section 24, T16; `docs/06-ai-agents.md`
  Section 14).

Evaluation activity must not accidentally define these criteria by, for
example, hard-coding a "correct" outcome for importance in a way that
constitutes an unapproved threshold or scoring rule.

---

## 9. Summarization Evaluation

Conceptual evaluation dimensions for summaries
(`docs/06-ai-agents.md` Section 4.5):

- **factual accuracy** — does the summary state what the source actually
  says?
- **source grounding** — is every claim traceable to the source content
  (Section 6)?
- **completeness of important facts** — does the summary omit something a
  user would need to understand the signal?
- **absence of unsupported claims** — no fact appears that the source does
  not support;
- **conciseness** — is the summary short enough to be useful for triage
  (`docs/02-functional-spec.md` Section 8.1)?
- **readability** — is the summary clear, in plain English?
- **distinction between facts and interpretation** — does the summary keep
  facts drawn from the source separate from any interpretation added
  (`docs/02-functional-spec.md` R4)?

**No exact summary length is decided here.** Expected summary form/length
remains open — **Q14** (`docs/02-functional-spec.md` Q14;
`docs/06-ai-agents.md` Section 4.5, "explicitly open") — and evaluation
approaches must be able to accommodate whatever form/length is eventually
approved, not presuppose one.

---

## 10. Near-Duplicate Evaluation

Evaluation should assess whether semantically equivalent stories are grouped
appropriately (`docs/06-ai-agents.md` Section 4.1; `docs/08-ingestion.md`
Section 9), considering:

- true duplicate/near-duplicate pairs correctly identified;
- unrelated stories incorrectly grouped (a false merge);
- the same story reported with different wording, correctly recognized as one
  story;
- updates or follow-on developments that should remain **distinct** signals
  rather than being merged into an earlier one.

**The similarity threshold is not defined here.** Preserved:

- **Q12** — duplicate vs. near-duplicate criteria
  (`docs/02-functional-spec.md` Q12).
- **T15** — the near-duplicate similarity threshold
  (`docs/03-technical-spec.md` Section 24, T15).

**No clustering algorithm is introduced.** Evaluation considers pairs and
groups exactly as the approved model defines them
(`docs/05-data-model.md` Section 18, "no complex duplicate graph") — it does
not imply a richer grouping structure than what is already approved.

---

## 11. Retrieval / RAG Evaluation

Strictly aligned with `docs/07-rag.md`; no new RAG concept is introduced.

### Retrieval

Does semantic search retrieve relevant stored information for a given query
(`docs/07-rag.md` Section 7, 9)?

### Context quality

Does the retrieved context actually contain the information required to
answer the question being asked, before Q&A synthesis even runs
(`docs/07-rag.md` Section 7)?

### Provenance

Can every retrieved passage be traced back to its original source
(`docs/07-rag.md` Section 11)?

### Q&A

Does the generated answer use **only** the supplied passages
(`docs/07-rag.md` Section 10; Section 6 above)?

**No reranking or advanced RAG evaluation architecture is introduced** — this
would exceed the MVP RAG design already fixed in `docs/07-rag.md`
Section 16. **No separate RAG evaluation service is introduced** — evaluation
of retrieval and Q&A remains a conceptual activity performed against the
existing Java-owned retrieval and Python-owned synthesis, not a new
component.

### Implemented by Task 8.6 (`docs/adr/0016-rag-evaluation.md`, `docs/07-rag.md` Section 27)

The generic RAG core gains `RagExecutionEvaluator` — a **deterministic,
after-the-fact** `Evaluator`. It consumes one immutable
`RagExecution` plus a small hand-authored `RagEvaluationDataset` and returns an
`EvaluationResult` carrying named `EvaluationMetric`s and free-text
`EvaluationFinding`s. It is **not** on the runtime path, calls **no** model, and
uses **no** LLM judge.

Deterministic metrics, from data already on the execution:

- **Retrieval** (when the dataset judges the query) — `recall@1/3/5/10`,
  `reciprocalRank`, `ndcg@10` of the retrieved ranking against the judged
  relevant passages. Corpus MRR / mean recall@k / mean nDCG are the plain mean
  across results (`RagEvaluationSummary`).
- **Answerability behaviour** (when judged) — `answerabilityAgreement` (1.0 when
  the system answered / abstained as the dataset expects, else 0.0). Answering a
  query judged unanswerable is also a grounding-risk finding.
- **Grounding / citation validity** (always, structural) — `citationValidity`
  (fraction of the answer's citations that resolve to a supplied context
  passage). Unresolved citations, provenance-mismatched citations, and an
  answered response with no citation are findings. This reuses the existing
  `Citation` / context model — no second citation model.
- **Execution outcome** — a note (dimension `EXECUTION_OUTCOME`) when a run
  cannot be meaningfully scored: no answer generated, empty retrieval, or no
  dataset judgement for the query.

**Not evaluated (deferred).** Semantic answer faithfulness / factuality, answer
relevance quality, summary quality, an LLM-as-a-judge, multilingual evaluation,
automatic pass/fail thresholds, production evaluation scheduling, and any
combined "overall RAG score". These remain open (Section 21;
`docs/adr/0016` § Open decisions). The **canonical dataset format and storage**
are also open — the core defines only the in-memory shape; the tests load a
versioned JSON fixture through a test-only loader.

---

## 12. Human Evaluation

Some semantic judgements are best assessed by a person, at least until (and
likely even after) more automated methods exist. Human evaluation may be used
to assess:

- relevance (Section 7);
- importance (Section 8);
- summary quality (Section 9);
- near-duplicate correctness (Section 10);
- Q&A correctness (Section 6, 11);
- overall usefulness.

**Who performs this evaluation is not specified.** Signal Engine's MVP is
intentionally simple and single-user oriented
(`docs/01-product-spec.md` Section 4); no formal annotation team, annotation
process, or evaluator role is defined or assumed. In practice, this is
expected to be the project's own contributors and, for their own data, the
single MVP user — but this document does not formalize that.

---

## 13. Automated Evaluation

Future automated evaluation may eventually include:

- deterministic assertions (Section 4);
- curated test cases;
- golden examples;
- regression tests (Section 15);
- retrieval evaluation (Section 11);
- structured-output validation (already a runtime requirement, not only an
  evaluation activity — `docs/03-technical-spec.md` Section 8.3);
- potentially model-based ("LLM-as-a-judge") evaluation for semantic quality.

**A full automated AI evaluation subsystem is explicitly not part of the
MVP** (`docs/01-product-spec.md` Section 8; `docs/03-technical-spec.md`
Section 16.3). This document does not define that subsystem's architecture,
and does not select an evaluation framework — no tool such as RAGAS,
DeepEval, LangSmith, or an equivalent is chosen or implied.

---

## 14. Datasets / Golden Sets

Evaluation of the kind described above eventually requires representative
examples. Conceptually, future evaluation data may cover:

- relevant vs. not-relevant examples;
- important vs. not-important examples;
- classification examples;
- near-duplicate pairs;
- summaries paired with their source content;
- retrieval queries with expected relevant results;
- grounded Q&A questions with expected supporting passages.

**None of this is created now.** This document does **not**:

- create a dataset;
- decide dataset size;
- decide a storage format;
- create an annotation schema;
- create benchmark files.

Representative examples for evaluation remain a **future requirement**, not
MVP work.

---

## 15. Regression Evaluation

Changing any of the following can change Signal Engine's observable behavior:

- the LLM model;
- the LLM provider;
- a prompt;
- the embedding model;
- the chunking strategy (`docs/07-rag.md` Section 4, once one is chosen);
- the retrieval strategy (`docs/07-rag.md` Section 7).

Future evaluation should therefore make it possible to **compare behavior
before and after such a change**, so a change's effect on quality is visible
rather than assumed. This is a principle, not a mechanism this document
builds: **no comparison system is built here, and no A/B testing
infrastructure is introduced.**

---

## 16. Evaluation and Provider Replaceability

Evaluation must remain independent of any specific provider or model, exactly
as the AI architecture itself does (`docs/06-ai-agents.md` Section 9,
Section 13).

For example: changing the initial local setup (Ollama with `gpt-oss:20b`) to
another provider or model must not require redesigning the evaluation
concepts in this document — only re-running the same conceptual evaluation
against the new provider/model.

**Evaluation operates against capability behavior/contracts** — the inputs and
outputs already fixed in `docs/06-ai-agents.md` Section 4–5 — **rather than
provider-specific internals.** No provider-specific benchmark is introduced;
evaluating "the classification capability" must mean the same thing
regardless of which provider or model implements it behind the
`LlmChatProvider`/`EmbeddingProvider` abstractions
(`docs/06-ai-agents.md` Section 9).

---

## 17. Evaluation and Observability

Two different questions must not be merged:

| | Question | Answered by |
|---|---|---|
| **Evaluation** | "Is the system producing **good** results?" | Human review, and eventually curated examples/automated checks (Sections 5–14) |
| **Observability** | "Is the system **running correctly**?" | Structured logs, correlation IDs, traces, minimal metrics, and Activity Records (`docs/03-technical-spec.md` Section 14; `docs/05-data-model.md` Section 13) |

Signal Engine's MVP already provides **basic operational visibility** — it
does **not** include a dedicated observability platform or a dedicated
evaluation platform (`docs/01-product-spec.md` Section 8;
`docs/03-technical-spec.md` Section 14.5, 25). These two concerns are kept
distinct in this document, exactly as they are kept distinct in the prior
specifications. A future integration between them (for example, using traced
capability calls as an input to evaluation) may be possible, but it is not
designed here.

---

## 18. MVP Boundaries

### MVP DOES

- basic deterministic testing (Section 4);
- schema validation (already a runtime requirement,
  `docs/03-technical-spec.md` Section 8.3);
- contract validation between Java and Python
  (`docs/03-technical-spec.md` Section 8.3, 16.6);
- provenance validation (Section 4);
- idempotency testing (`docs/08-ingestion.md` Section 15);
- processing-state testing (`docs/08-ingestion.md` Section 14);
- basic AI capability tests where practical, using recorded/fake fixtures for
  deterministic test behavior (`docs/03-technical-spec.md` Section 16.2);
- basic regression coverage as implementation evolves (Section 15);
- the ability to inspect failures/results through Activity Records and logs
  (`docs/05-data-model.md` Section 13; `docs/03-technical-spec.md`
  Section 14).

### MVP DOES NOT include

- a dedicated evaluation subsystem;
- an evaluation microservice;
- an evaluation dashboard;
- an automated LLM-as-a-judge platform;
- a benchmark platform;
- production continuous evaluation;
- A/B testing;
- a model leaderboard;
- an external evaluation SaaS;
- a large curated benchmark dataset.

This matches, and does not contradict, `docs/01-product-spec.md` Section 8,
`docs/03-technical-spec.md` Section 16.3 and Section 25, and
`docs/06-ai-agents.md` Section 13.

---

## 19. Failure Modes Evaluation Should Catch

| Failure | What evaluation should detect |
|---|---|
| Hallucinated summary | An unsupported claim in a summary |
| Incorrect relevance | A relevant item rejected, or noise accepted as relevant |
| Incorrect importance | An important item missed, or noise promoted to a Signal |
| Wrong classification | The wrong area(s) assigned |
| False duplicate | Distinct stories incorrectly merged |
| Missed duplicate | The same story treated as new |
| Unsupported Q&A | An answer not supported by the supplied passages |
| Invalid citation | A citation that does not refer to a supplied passage |
| Provenance loss | Output that cannot be traced back to its source |
| Duplicate persistence | Reprocessing creates duplicate business records |

No additional product behavior is implied beyond what
`docs/01-08` already establish; this table only names the failure of each
already-approved behavior.

---

## 20. Evaluation Lifecycle

A conceptual future quality loop — not a platform, and not built here:

```text
Define expected behavior
        ↓
Create representative examples
        ↓
Run capability/system evaluation
        ↓
Inspect failures
        ↓
Improve implementation/model/prompt
        ↓
Re-run evaluation
        ↓
Compare results
```

This loop describes *how quality improvement would work in principle*; it is
not a specification for tooling, a schedule, or an organizational process.

---

## 21. Open Questions

This document resolves none of the following; each is carried forward from
the specification that originated it, using existing identifiers only.

- **Q4 / T16** — Signal-selection criteria and signal-decision guardrails
  (`docs/02-functional-spec.md` Q4; `docs/03-technical-spec.md` Section 24,
  T16; Section 8 above).
- **Q12 / T15** — Near-duplicate criteria and threshold
  (`docs/02-functional-spec.md` Q12; `docs/03-technical-spec.md` Section 24,
  T15; Section 10 above).
- **Q14** — Summary form/length (`docs/02-functional-spec.md` Q14;
  Section 9 above).
- **Q21** — Whether question answering ever supports multi-turn context
  (`docs/02-functional-spec.md` Q21; `docs/06-ai-agents.md` Section 14).
- **Q24 / T19** — Future multilingual behavior
  (`docs/02-functional-spec.md` Q24; `docs/03-technical-spec.md` Section 24,
  T19).
- **T3** — Embedding model / vector dimension
  (`docs/03-technical-spec.md` Section 24, T3; Section 5, 11 above).
- **T7** — pgvector index strategy (`docs/03-technical-spec.md` Section 24,
  T7; Section 11 above).
- **T9** — Retry/timeout values, where evaluation depends on how failures are
  bounded (`docs/03-technical-spec.md` Section 24, T9).

**No exact evaluation target (a precision/recall number, a coverage
percentage, or a pass/fail threshold) is approved anywhere in the project.**
This remains open in general, not tied to a single existing question ID; any
future evaluation target must be an explicit product/technical decision, not
something this document infers.

No new question identifier is created; every item above uses an identifier
already established in `docs/02-functional-spec.md` or
`docs/03-technical-spec.md`.

---

## 22. Decisions vs. Open Questions

### Already decided (restated from prior documents, not new here)

- Source grounding is mandatory (`docs/01-product-spec.md` Section 3).
- Provenance is mandatory (`docs/01-product-spec.md` Section 3;
  `docs/05-data-model.md` Section 15).
- AI output is structured and validated (`docs/03-technical-spec.md`
  Section 8.3, 9.4).
- Java owns business state (`docs/04-architecture.md` Section 3.2).
- Python capabilities are replaceable and provider-independent
  (`docs/06-ai-agents.md` Section 9, 13).
- There is no dedicated evaluation subsystem in the MVP
  (`docs/01-product-spec.md` Section 8; `docs/03-technical-spec.md`
  Section 16.3).

### Conceptually defined here (this document's contribution)

- The **quality dimensions** relevant to Signal Engine (Section 3).
- The distinction between **deterministic** and **semantic** evaluation
  (Section 4).
- The **role of human evaluation** in the MVP (Section 12).
- The **regression evaluation principle** — that behavior-affecting changes
  should be comparable before/after (Section 15).
- The **grounding evaluation principle** — what "grounded" means to verify,
  for summaries and for Q&A (Section 6).
- The **distinction between evaluation and observability** (Section 17).

### Still open

Every item listed in Section 21, and only those items — no additional open
question is introduced beyond what is already known to be undecided.

---

## 23. Summary

Signal Engine should be designed so that the quality of its outputs — how
relevant, important, accurate, grounded, and reliable they are — can be
measured and improved over time, while keeping evaluation **independent**
(from any specific provider or model), **replaceable** (as capabilities and
models change), **evidence-based** (grounded in observable behavior, not
assumption), and **deliberately lightweight in the MVP**. Deterministic system
correctness (provenance, idempotency, schema validity, processing state) can
already be checked objectively today; semantic quality (relevance, importance,
summarization, near-duplicate grouping, grounded Q&A) is described
conceptually here and is expected to rely on human review for now, with
automated and dataset-based evaluation as a clearly deferred future
capability — not a dedicated subsystem, dashboard, benchmark platform, or
framework introduced by this document.
