package org.signalengine.rag.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Corpus-level aggregation of per-execution {@link EvaluationResult}s: the deterministic mean of a
 * named metric across a set of runs (docs/adr/0016-rag-evaluation.md).
 *
 * <p>This is how a query-set number is obtained &mdash; mean {@code reciprocalRank} across a set is
 * the MRR, mean {@code recall@5} is corpus recall@5, and so on. It is a plain arithmetic mean over
 * the results that actually carry the metric; it invents no weighting and produces no single
 * combined score. Runs that did not produce the metric (e.g. an unjudged query) are simply not
 * counted.
 */
public final class RagEvaluationSummary {

  private RagEvaluationSummary() {}

  /** Number of results in {@code results} that carry a metric with this exact name. */
  public static int count(List<EvaluationResult> results, String metricName) {
    Objects.requireNonNull(metricName, "metricName");
    return (int)
        results.stream().filter(result -> result.metricValue(metricName).isPresent()).count();
  }

  /**
   * Arithmetic mean of the metric with this name across the results that carry it. Empty when no
   * result carries it &mdash; there is no meaningful default.
   */
  public static java.util.OptionalDouble mean(List<EvaluationResult> results, String metricName) {
    Objects.requireNonNull(results, "results");
    Objects.requireNonNull(metricName, "metricName");
    return results.stream()
        .map(result -> result.metricValue(metricName))
        .filter(java.util.Optional::isPresent)
        .mapToDouble(java.util.Optional::get)
        .average();
  }
}
