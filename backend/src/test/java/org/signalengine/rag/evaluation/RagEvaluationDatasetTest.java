package org.signalengine.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link RagEvaluationDataset} / {@link RelevanceJudgement} and the versioned JSON fixture. */
class RagEvaluationDatasetTest {

  @Test
  void theCheckedInFixtureLoadsWithItsVersionAndJudgements() {
    RagEvaluationDataset dataset = RagEvaluationDatasets.load("retrieval-eval-v1.json");

    assertThat(dataset.version()).isEqualTo("2026-09-08");
    assertThat(dataset.size()).isEqualTo(6);
    assertThat(dataset.judgementFor("What does Beta Ltd manufacture?"))
        .hasValueSatisfying(
            judgement -> {
              assertThat(judgement.answerable()).isTrue();
              assertThat(judgement.relevantPassageIds()).containsExactly("eval-acme::2");
              assertThat(judgement.relevantPassageGrades()).containsEntry("eval-acme::2", 2);
            });
    assertThat(dataset.judgementFor("Who is the chief executive of Acme Corp?"))
        .hasValueSatisfying(
            judgement -> {
              assertThat(judgement.answerable()).isFalse();
              assertThat(judgement.relevantPassageIds()).isEmpty();
            });
    assertThat(dataset.judgementFor("not in the fixture")).isEmpty();
  }

  @Test
  void aJudgementRejectsBlankQueryTextBlankPassageIdsAndGradesBelowOne() {
    assertThatThrownBy(() -> new RelevanceJudgement(" ", Map.of(), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RelevanceJudgement("q", Map.of("p", 0), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RelevanceJudgement("q", java.util.Collections.singletonMap(" ", 1), true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void judgementFactoriesProduceTheExpectedShape() {
    RelevanceJudgement answerable = RelevanceJudgement.answerable("q", Set.of("a", "b"));
    assertThat(answerable.answerable()).isTrue();
    assertThat(answerable.relevantPassageGrades()).containsOnlyKeys("a", "b").containsValue(1);

    RelevanceJudgement unanswerable = RelevanceJudgement.unanswerable("q2");
    assertThat(unanswerable.answerable()).isFalse();
    assertThat(unanswerable.relevantPassageIds()).isEmpty();
  }

  @Test
  void aDatasetRejectsABlankVersionAndDuplicateQueries() {
    assertThatThrownBy(() -> new RagEvaluationDataset(" ", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RagEvaluationDataset(
                    "v1",
                    List.of(
                        RelevanceJudgement.unanswerable("same"),
                        RelevanceJudgement.unanswerable("same"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
  }
}
