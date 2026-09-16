package org.signalengine.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;

/** The evaluation value types: {@link EvaluationMetric}, {@link EvaluationResult}. */
class EvaluationValueTypesTest {

  private static final ComponentDescriptor EVALUATOR =
      new ComponentDescriptor(RagComponentType.EVALUATOR, "test-evaluator", "1");

  @Test
  void aMetricCarriesItsDimensionNameAndValue() {
    EvaluationMetric metric =
        new EvaluationMetric(
            EvaluationDimension.RETRIEVAL_QUALITY, "recall@5", 0.8, Map.of("k", "5"));

    assertThat(metric.dimension()).isEqualTo(EvaluationDimension.RETRIEVAL_QUALITY);
    assertThat(metric.name()).isEqualTo("recall@5");
    assertThat(metric.value()).isEqualTo(0.8);
    assertThat(metric.details()).containsEntry("k", "5");
  }

  @Test
  void aMetricRejectsABlankNameANonFiniteValueAndBlankDetailKeys() {
    assertThatThrownBy(() -> EvaluationMetric.of(EvaluationDimension.RETRIEVAL_QUALITY, " ", 1.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> EvaluationMetric.of(EvaluationDimension.RETRIEVAL_QUALITY, "x", Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EvaluationMetric(
                    EvaluationDimension.RETRIEVAL_QUALITY,
                    "x",
                    1.0,
                    java.util.Collections.singletonMap(" ", "v")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anEvaluationResultExposesMetricValuesByName() {
    UUID executionId = UUID.randomUUID();
    EvaluationResult result =
        new EvaluationResult(
            executionId,
            EVALUATOR,
            List.of(EvaluationFinding.of(EvaluationDimension.EXECUTION_OUTCOME, "ok")),
            List.of(
                EvaluationMetric.of(EvaluationDimension.RETRIEVAL_QUALITY, "recall@5", 1.0),
                EvaluationMetric.of(
                    EvaluationDimension.CITATION_CORRECTNESS, "citationValidity", 0.5)),
            Map.of("datasetVersion", "2026-09-08"));

    assertThat(result.executionId()).isEqualTo(executionId);
    assertThat(result.metricValue("recall@5")).contains(1.0);
    assertThat(result.metricValue("citationValidity")).contains(0.5);
    assertThat(result.metricValue("nonexistent")).isEmpty();
    assertThat(result.findings()).hasSize(1);
  }

  @Test
  void anEvaluationResultDefaultsNullCollectionsToEmptyAndRejectsNullElements() {
    EvaluationResult result = new EvaluationResult(UUID.randomUUID(), EVALUATOR, null, null, null);
    assertThat(result.findings()).isEmpty();
    assertThat(result.metrics()).isEmpty();
    assertThat(result.metadata()).isEmpty();

    assertThatThrownBy(
            () ->
                new EvaluationResult(
                    UUID.randomUUID(),
                    EVALUATOR,
                    List.of(),
                    java.util.Arrays.asList((EvaluationMetric) null),
                    Map.of()))
        .isInstanceOf(NullPointerException.class); // List.copyOf rejects the null element
  }
}
