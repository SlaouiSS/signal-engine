/**
 * Evaluation of RAG runs &mdash; a subsystem that sits <b>outside</b> the runtime pipeline
 * (docs/07-rag.md Section 21; docs/09-evaluation.md Section 11; CLAUDE.md Section 17).
 *
 * <pre>
 *   RagExecution  &rarr;  Evaluator  &rarr;  EvaluationResult  &rarr;  (evaluation store, later task)
 * </pre>
 *
 * <p>Structural independence is the point:
 *
 * <ul>
 *   <li>{@link org.signalengine.rag.evaluation.Evaluator} takes a finished, immutable {@link
 *       org.signalengine.rag.execution.RagExecution}. It is handed no {@link
 *       org.signalengine.rag.pipeline.RagPipeline} and no stage, so it cannot re-run, drive or
 *       mutate the pipeline.
 *   <li>Nothing in the runtime pipeline packages depends on this package. Evaluation never becomes
 *       a runtime dependency of answering a query.
 *   <li>An evaluator may assess retrieval quality, context quality, answer relevance,
 *       grounding/faithfulness and citation correctness. {@link
 *       org.signalengine.rag.evaluation.EvaluationFinding} carries a dimension and a free-text
 *       observation; {@link org.signalengine.rag.evaluation.EvaluationMetric} carries a named
 *       deterministic number. Neither is combined into a single "overall RAG score".
 * </ul>
 *
 * <p><b>Task 8.6</b> adds the supplied {@link
 * org.signalengine.rag.evaluation.RagExecutionEvaluator} (docs/adr/0016-rag-evaluation.md): a
 * deterministic evaluator that scores one {@link org.signalengine.rag.execution.RagExecution}
 * against a hand-authored {@link org.signalengine.rag.evaluation.RagEvaluationDataset} &mdash;
 * ranking metrics ({@code recall@k}, {@code reciprocalRank}, {@code ndcg@10}), answerability
 * agreement, and structural citation validity. It is still <b>no LLM judge</b> and does <b>no</b>
 * semantic factuality scoring; those stay open (docs/09-evaluation.md Section 21; docs/adr/0016
 * &sect; Open decisions). {@link org.signalengine.rag.evaluation.RagEvaluationSummary} takes the
 * arithmetic mean of a metric across a set of results (corpus MRR / recall@k / nDCG).
 *
 * <p>The future improvement loop (evaluation results &rarr; improvement analysis &rarr; experiment
 * &rarr; evaluation &rarr; proposal &rarr; human approval &rarr; new RAG configuration) is
 * documented in docs/07-rag.md Section 21 and is not modelled here. An improvement step compares
 * {@code RagExecution}s and swaps components behind the existing contracts; it never edits RAG core
 * source.
 */
package org.signalengine.rag.evaluation;
