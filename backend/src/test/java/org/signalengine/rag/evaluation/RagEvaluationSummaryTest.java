package org.signalengine.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.execution.RagExecution;

/**
 * {@link RagEvaluationSummary}: a query-set number (MRR, corpus recall@k, mean nDCG) is the plain
 * mean of the per-execution metric across the results that carry it.
 */
class RagEvaluationSummaryTest {

  private final RagEvaluationEvaluatorHarness harness = new RagEvaluationEvaluatorHarness();

  @Test
  void meanReciprocalRankAcrossExecutionsIsTheMrr() {
    // three judged queries: first relevant at rank 1, rank 2, and not retrieved -> RR 1, 0.5, 0
    List<EvaluationResult> results =
        List.of(
            harness.evaluate(
                "How much is Acme paying to acquire Beta?",
                List.of("eval-acme::0", "eval-acme::1")),
            harness.evaluate(
                "What does Beta Ltd manufacture?", List.of("eval-noise::0", "eval-acme::2")),
            harness.evaluate(
                "When is the Acme and Beta deal expected to close?",
                List.of("eval-noise::0", "eval-noise::1")));

    assertThat(RagEvaluationSummary.mean(results, "reciprocalRank")).isPresent();
    assertThat(RagEvaluationSummary.mean(results, "reciprocalRank").getAsDouble())
        .isCloseTo(0.5, within(1e-9));
    assertThat(RagEvaluationSummary.count(results, "reciprocalRank")).isEqualTo(3);
  }

  @Test
  void meanRecallAtKIgnoresRunsThatDoNotCarryTheMetric() {
    List<EvaluationResult> results =
        List.of(
            harness.evaluate(
                "What does Beta Ltd manufacture?", List.of("eval-acme::2")), // recall@5 = 1.0
            harness.evaluate("a query the fixture never heard of", List.of("x::0"))); // no metric

    assertThat(RagEvaluationSummary.count(results, "recall@5")).isEqualTo(1);
    assertThat(RagEvaluationSummary.mean(results, "recall@5")).hasValue(1.0);
  }

  @Test
  void meanOfAMetricNoResultCarriesIsEmpty() {
    List<EvaluationResult> results =
        List.of(harness.evaluate("a query the fixture never heard of", List.of("x::0")));

    assertThat(RagEvaluationSummary.mean(results, "recall@5")).isEmpty();
    assertThat(RagEvaluationSummary.count(results, "recall@5")).isZero();
  }

  /**
   * Small local harness: evaluate an answerable query whose answer cites the first retrieved id.
   */
  private static final class RagEvaluationEvaluatorHarness {

    private final RagExecutionEvaluator evaluator =
        new RagExecutionEvaluator(RagEvaluationDatasets.load("retrieval-eval-v1.json"));

    EvaluationResult evaluate(String queryText, List<String> retrievedIds) {
      RagExecution execution =
          RagExecutionFixtures.execution(
              queryText, retrievedIds, retrievedIds, true, List.of(retrievedIds.get(0)));
      return evaluator.evaluate(execution);
    }
  }
}
