package org.signalengine.rag.evaluation;

import java.util.Map;

/**
 * One observation an {@link Evaluator} made about a RAG run.
 *
 * <p>Deliberately minimal: a dimension and a free-text observation. No numeric score, pass/fail
 * verdict or metric name is modelled &mdash; the metric set and any LLM-judge approach are open
 * questions for a later task (docs/09-evaluation.md Section 21).
 *
 * @param dimension which aspect of the run this is about; never {@code null}
 * @param observation what the evaluator observed; never blank
 * @param details open, immutable supporting detail (which passages, which claim&hellip;); keys
 *     never blank
 */
public record EvaluationFinding(
    EvaluationDimension dimension, String observation, Map<String, String> details) {

  public EvaluationFinding {
    if (dimension == null) {
      throw new IllegalArgumentException("dimension must not be null");
    }
    if (observation == null || observation.isBlank()) {
      throw new IllegalArgumentException("observation must not be blank");
    }
    details = details == null ? Map.of() : Map.copyOf(details);
    if (details.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
      throw new IllegalArgumentException("detail keys must not be blank");
    }
  }

  public static EvaluationFinding of(EvaluationDimension dimension, String observation) {
    return new EvaluationFinding(dimension, observation, Map.of());
  }
}
