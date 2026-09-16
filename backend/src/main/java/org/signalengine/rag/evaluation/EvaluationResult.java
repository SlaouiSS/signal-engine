package org.signalengine.rag.evaluation;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.signalengine.rag.ComponentDescriptor;

/**
 * What an {@link Evaluator} produced for one RAG run. Carries no ability to act on the run &mdash;
 * it is a read-only assessment that a later task persists to an evaluation store.
 *
 * <p>Two parallel collections: {@link #metrics()} are named deterministic numbers, {@link
 * #findings()} are free-text observations. Neither is aggregated into a single score
 * (docs/09-evaluation.md Section 10).
 *
 * @param executionId the {@link org.signalengine.rag.execution.RagExecution} this assesses; never
 *     {@code null}
 * @param evaluator identity and version of the evaluator that produced this; never {@code null}
 * @param findings the observations made, in no required order; never {@code null}, may be empty
 * @param metrics the numeric measurements computed, in no required order; never {@code null}, may
 *     be empty
 * @param metadata open, immutable result-level metadata; keys never blank
 */
public record EvaluationResult(
    UUID executionId,
    ComponentDescriptor evaluator,
    List<EvaluationFinding> findings,
    List<EvaluationMetric> metrics,
    Map<String, String> metadata) {

  public EvaluationResult {
    if (executionId == null) {
      throw new IllegalArgumentException("executionId must not be null");
    }
    if (evaluator == null) {
      throw new IllegalArgumentException("evaluator must not be null");
    }
    findings = findings == null ? List.of() : List.copyOf(findings);
    if (findings.stream().anyMatch(finding -> finding == null)) {
      throw new IllegalArgumentException("findings must not contain null");
    }
    metrics = metrics == null ? List.of() : List.copyOf(metrics);
    if (metrics.stream().anyMatch(metric -> metric == null)) {
      throw new IllegalArgumentException("metrics must not contain null");
    }
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    if (metadata.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
      throw new IllegalArgumentException("metadata keys must not be blank");
    }
  }

  /** The value of the metric with this exact name, if the evaluator computed it. */
  public java.util.Optional<Double> metricValue(String name) {
    return metrics.stream()
        .filter(metric -> metric.name().equals(name))
        .map(EvaluationMetric::value)
        .findFirst();
  }
}
