package org.signalengine.rag.evaluation;

/**
 * An aspect of a RAG run an {@link Evaluator} can assess independently (docs/09-evaluation.md
 * Section 11). The dimension only says <i>what</i> a finding or {@link EvaluationMetric} is about;
 * it carries no scale of its own.
 */
public enum EvaluationDimension {
  RETRIEVAL_QUALITY,
  CONTEXT_QUALITY,
  ANSWER_RELEVANCE,
  GROUNDING_FAITHFULNESS,
  CITATION_CORRECTNESS,

  /**
   * Whether the run is well-formed enough to be assessed at all &mdash; a missing answer, an empty
   * retrieval, or no dataset judgement for the query. Not a quality score; a "can this even be
   * evaluated" note (docs/09-evaluation.md Section 11; Task 8.6 scope item D).
   */
  EXECUTION_OUTCOME
}
