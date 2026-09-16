package org.signalengine.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.generation.RagAnswer;

/**
 * {@link RagExecutionEvaluator}: deterministic scoring of one {@link RagExecution} against the
 * versioned evaluation fixture &mdash; ranking metrics, answerability agreement, structural
 * citation validity, and "cannot evaluate" notes.
 */
class RagExecutionEvaluatorTest {

  private static final String DATASET_VERSION = "2026-09-08";

  private final RagEvaluationDataset dataset = RagEvaluationDatasets.load("retrieval-eval-v1.json");
  private final RagExecutionEvaluator evaluator = new RagExecutionEvaluator(dataset);

  private static final String ACME_PRICE_QUERY = "How much is Acme paying to acquire Beta?";
  private static final String BETA_PRODUCT_QUERY = "What does Beta Ltd manufacture?";
  private static final String PENALTIES_QUERY =
      "What penalties does the new automated-decision rule impose for non-compliance?";

  @Test
  void computesRecallAtKForARankingThatContainsBothRelevantPassages() {
    // relevant for this query: eval-acme::0 (grade 2), eval-acme::1 (grade 1)
    RagExecution execution =
        RagExecutionFixtures.execution(
            ACME_PRICE_QUERY,
            List.of("eval-acme::0", "eval-noise::9", "eval-acme::1", "eval-noise::8"),
            List.of("eval-acme::0", "eval-acme::1"),
            true,
            List.of("eval-acme::0"));

    EvaluationResult result = evaluator.evaluate(execution);

    assertThat(result.metricValue("recall@1")).contains(0.5); // 1 of 2 relevant in top 1
    assertThat(result.metricValue("recall@3")).contains(1.0); // both in top 3
    assertThat(result.metricValue("recall@5")).contains(1.0);
    assertThat(result.metricValue("recall@10")).contains(1.0);
    assertThat(result.metricValue("reciprocalRank")).contains(1.0); // first relevant at rank 1
  }

  @Test
  void computesNdcgForAGradedRanking() {
    RagExecution ideal =
        RagExecutionFixtures.execution(
            ACME_PRICE_QUERY,
            List.of("eval-acme::0", "eval-acme::1"),
            List.of("eval-acme::0", "eval-acme::1"),
            true,
            List.of("eval-acme::0"));
    RagExecution worse =
        RagExecutionFixtures.execution(
            ACME_PRICE_QUERY,
            List.of("eval-noise::1", "eval-acme::1", "eval-acme::0"),
            List.of("eval-acme::1", "eval-acme::0"),
            true,
            List.of("eval-acme::0"));

    assertThat(evaluator.evaluate(ideal).metricValue("ndcg@10")).contains(1.0);
    double worseNdcg = evaluator.evaluate(worse).metricValue("ndcg@10").orElseThrow();
    assertThat(worseNdcg).isGreaterThan(0.0).isLessThan(1.0);
  }

  @Test
  void multipleRelevantPassagesAreReflectedInRecall() {
    RagExecution onlyOne =
        RagExecutionFixtures.execution(
            ACME_PRICE_QUERY,
            List.of("eval-acme::0", "eval-noise::1", "eval-noise::2"),
            List.of("eval-acme::0"),
            true,
            List.of("eval-acme::0"));

    EvaluationResult result = evaluator.evaluate(onlyOne);
    assertThat(result.metricValue("recall@5")).contains(0.5); // 1 of 2 relevant retrieved
    assertThat(result.findings())
        .anySatisfy(
            finding ->
                assertThat(finding.dimension()).isEqualTo(EvaluationDimension.RETRIEVAL_QUALITY));
  }

  @Test
  void anEmptyRetrievalResultIsRecordedAsAnExecutionOutcomeFinding() {
    RagExecution empty =
        RagExecutionFixtures.execution(BETA_PRODUCT_QUERY, List.of(), List.of(), false, List.of());

    EvaluationResult result = evaluator.evaluate(empty);

    assertThat(result.findings())
        .anySatisfy(
            finding -> {
              assertThat(finding.dimension()).isEqualTo(EvaluationDimension.EXECUTION_OUTCOME);
              assertThat(finding.observation()).contains("no passages");
            });
    assertThat(result.metricValue("recall@5")).contains(0.0); // relevant passage never retrieved
  }

  @Test
  void correctCitationReferencesScoreFullCitationValidity() {
    RagExecution execution =
        RagExecutionFixtures.execution(
            BETA_PRODUCT_QUERY,
            List.of("eval-acme::2", "eval-noise::1"),
            List.of("eval-acme::2", "eval-noise::1"),
            true,
            List.of("eval-acme::2"));

    EvaluationResult result = evaluator.evaluate(execution);

    assertThat(result.metricValue("citationValidity")).contains(1.0);
    assertThat(result.findings())
        .noneMatch(finding -> finding.dimension() == EvaluationDimension.GROUNDING_FAITHFULNESS);
  }

  @Test
  void anUnknownCitationLowersCitationValidityAndRaisesAGroundingFinding() {
    RagExecution execution =
        RagExecutionFixtures.execution(
            BETA_PRODUCT_QUERY,
            List.of("eval-acme::2"),
            List.of("eval-acme::2"),
            true,
            List.of("eval-acme::2", "ghost::0"));

    EvaluationResult result = evaluator.evaluate(execution);

    assertThat(result.metricValue("citationValidity")).contains(0.5);
    assertThat(result.findings())
        .anySatisfy(
            finding -> {
              assertThat(finding.dimension()).isEqualTo(EvaluationDimension.GROUNDING_FAITHFULNESS);
              assertThat(finding.details().get("unknownPassageIds")).contains("ghost::0");
            });
  }

  @Test
  void aGroundedAnswerWithNoCitationScoresZeroCitationValidity() {
    RagAnswer answeredWithoutCiting =
        new RagAnswer(true, "an answer", List.of(), java.util.Map.of());
    RagExecution execution =
        RagExecutionFixtures.execution(
            BETA_PRODUCT_QUERY,
            List.of("eval-acme::2"),
            List.of("eval-acme::2"),
            answeredWithoutCiting);

    EvaluationResult result = evaluator.evaluate(execution);

    assertThat(result.metricValue("citationValidity")).contains(0.0);
    assertThat(result.findings())
        .anySatisfy(finding -> assertThat(finding.observation()).contains("cites no passage"));
  }

  @Test
  void abstainingOnAnUnsupportedQuestionIsScoredAsCorrectBehaviour() {
    RagExecution execution =
        RagExecutionFixtures.execution(
            PENALTIES_QUERY,
            List.of("eval-law::0", "eval-law::1"),
            List.of("eval-law::0", "eval-law::1"),
            false,
            List.of());

    EvaluationResult result = evaluator.evaluate(execution);

    assertThat(result.metricValue("answerabilityAgreement")).contains(1.0);
    assertThat(result.findings())
        .noneMatch(f -> f.dimension() == EvaluationDimension.GROUNDING_FAITHFULNESS);
  }

  @Test
  void answeringAnUnsupportedQuestionIsScoredAsWrongBehaviourAndFlaggedAsAGroundingRisk() {
    RagExecution execution =
        RagExecutionFixtures.execution(
            PENALTIES_QUERY,
            List.of("eval-law::0"),
            List.of("eval-law::0"),
            true,
            List.of("eval-law::0"));

    EvaluationResult result = evaluator.evaluate(execution);

    assertThat(result.metricValue("answerabilityAgreement")).contains(0.0);
    assertThat(result.findings())
        .anySatisfy(
            finding -> {
              assertThat(finding.dimension()).isEqualTo(EvaluationDimension.GROUNDING_FAITHFULNESS);
              assertThat(finding.observation()).contains("unanswerable");
            });
  }

  @Test
  void anExecutionWithNoAnswerIsNotScoredForAnswerabilityOrGrounding() {
    RagExecution execution =
        RagExecutionFixtures.withoutAnswer(BETA_PRODUCT_QUERY, List.of("eval-acme::2"));

    EvaluationResult result = evaluator.evaluate(execution);

    assertThat(result.metricValue("answerabilityAgreement")).isEmpty();
    assertThat(result.metricValue("citationValidity")).isEmpty();
    assertThat(result.findings())
        .anySatisfy(
            finding -> {
              assertThat(finding.dimension()).isEqualTo(EvaluationDimension.EXECUTION_OUTCOME);
              assertThat(finding.observation()).contains("no answer");
            });
    // retrieval is still scored
    assertThat(result.metricValue("recall@5")).isPresent();
  }

  @Test
  void anUnjudgedQueryProducesAnExecutionOutcomeNoteAndNoRankingMetrics() {
    RagExecution execution =
        RagExecutionFixtures.execution(
            "a query the fixture has never heard of",
            List.of("x::0", "x::1"),
            List.of("x::0"),
            true,
            List.of("x::0"));

    EvaluationResult result = evaluator.evaluate(execution);

    assertThat(result.metricValue("recall@5")).isEmpty();
    assertThat(result.metricValue("answerabilityAgreement")).isEmpty();
    assertThat(result.findings())
        .anySatisfy(finding -> assertThat(finding.observation()).contains("no judgement"));
    assertThat(result.metadata()).containsEntry("judged", "false");
    // grounding is still checked structurally
    assertThat(result.metricValue("citationValidity")).contains(1.0);
  }

  @Test
  void theEvaluationResultCarriesTheExecutionIdEvaluatorIdentityAndMetadata() {
    RagExecution execution =
        RagExecutionFixtures.execution(
            BETA_PRODUCT_QUERY,
            List.of("eval-acme::2"),
            List.of("eval-acme::2"),
            true,
            List.of("eval-acme::2"));

    EvaluationResult result = evaluator.evaluate(execution);

    assertThat(result.executionId()).isEqualTo(execution.executionId());
    assertThat(result.evaluator().type()).isEqualTo(RagComponentType.EVALUATOR);
    assertThat(result.evaluator().implementationId()).isEqualTo("rag-execution-evaluator");
    assertThat(result.evaluator().version()).contains("dataset=" + DATASET_VERSION);
    assertThat(result.metadata())
        .containsEntry("datasetVersion", DATASET_VERSION)
        .containsEntry("judged", "true")
        .containsEntry("generated", "true");
    assertThat(result.findings()).isNotEmpty();
    assertThat(result.metrics()).isNotEmpty();
    assertThat(result.metrics())
        .allSatisfy(metric -> assertThat(Double.isFinite(metric.value())).isTrue());
  }

  @Test
  void repeatedEvaluationOfTheSameExecutionIsDeterministic() {
    RagExecution execution =
        RagExecutionFixtures.execution(
            ACME_PRICE_QUERY,
            List.of("eval-acme::1", "eval-noise::0", "eval-acme::0"),
            List.of("eval-acme::1", "eval-acme::0"),
            true,
            List.of("eval-acme::0", "eval-acme::1"));

    EvaluationResult first = evaluator.evaluate(execution);
    EvaluationResult second = evaluator.evaluate(execution);

    assertThat(second).isEqualTo(first);
  }

  @Test
  void nullDatasetOrExecutionIsRejected() {
    assertThatThrownBy(() -> new RagExecutionEvaluator(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> evaluator.evaluate(null)).isInstanceOf(NullPointerException.class);
  }
}
