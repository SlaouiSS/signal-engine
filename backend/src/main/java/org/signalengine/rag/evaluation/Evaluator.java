package org.signalengine.rag.evaluation;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.execution.RagExecution;

/**
 * Assesses a finished RAG run. The only input is an immutable {@link RagExecution}; no pipeline or
 * stage is passed, so an evaluator cannot re-run, drive or mutate the pipeline it is evaluating.
 *
 * <p>Evaluation is never called from the runtime pipeline &mdash; it is an after-the-fact activity
 * (docs/09-evaluation.md Section 2). This task defines no metric and no LLM judge; those are open.
 */
@FunctionalInterface
public interface Evaluator {

  EvaluationResult evaluate(RagExecution execution);

  /** Identity and version of the concrete implementation; overridden by real evaluators. */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.EVALUATOR);
  }
}
