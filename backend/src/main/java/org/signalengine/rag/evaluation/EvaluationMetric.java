package org.signalengine.rag.evaluation;

import java.util.Map;

/**
 * One deterministic numeric measurement an {@link Evaluator} computed for a RAG run
 * (docs/09-evaluation.md Section 4, 11; docs/adr/0016-rag-evaluation.md).
 *
 * <p>Distinct from {@link EvaluationFinding}: a finding is a free-text observation, a metric is a
 * named number. Kept intentionally flat &mdash; a stable {@code name}, a {@code value}, the {@link
 * EvaluationDimension} it belongs to, and open {@code details} explaining what was measured. There
 * is <b>no</b> pass/fail verdict, no weighting, and no composite "overall score": a caller that
 * wants one derives it (docs/09-evaluation.md Section 10, 21).
 *
 * @param dimension which aspect of the run this measures; never {@code null}
 * @param name stable metric identifier, e.g. {@code "recall@5"}, {@code "reciprocalRank"}, {@code
 *     "citationValidity"}; never blank
 * @param value the measured value; must be finite. Ratio metrics are in {@code [0, 1]}; the record
 *     does not enforce a range because not every metric is a ratio
 * @param details open, immutable supporting detail (k, retrieved count, which ids&hellip;); keys
 *     never blank
 */
public record EvaluationMetric(
    EvaluationDimension dimension, String name, double value, Map<String, String> details) {

  public EvaluationMetric {
    if (dimension == null) {
      throw new IllegalArgumentException("dimension must not be null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("value must be finite, was " + value);
    }
    details = details == null ? Map.of() : Map.copyOf(details);
    if (details.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
      throw new IllegalArgumentException("detail keys must not be blank");
    }
  }

  public static EvaluationMetric of(EvaluationDimension dimension, String name, double value) {
    return new EvaluationMetric(dimension, name, value, Map.of());
  }
}
