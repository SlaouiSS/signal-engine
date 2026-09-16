package org.signalengine.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The deterministic ranking metrics used by {@link RagExecutionEvaluator}. */
class RetrievalMetricsTest {

  private final List<String> ranked = List.of("a", "b", "c", "d");

  @Test
  void recallAtKCountsRelevantPassagesInTheTopK() {
    assertThat(RetrievalMetrics.recallAtK(ranked, Set.of("c"), 1)).isZero();
    assertThat(RetrievalMetrics.recallAtK(ranked, Set.of("c"), 3)).isEqualTo(1.0);
    assertThat(RetrievalMetrics.recallAtK(ranked, Set.of("a", "c"), 2)).isEqualTo(0.5);
    assertThat(RetrievalMetrics.recallAtK(ranked, Set.of("a", "c"), 10)).isEqualTo(1.0);
  }

  @Test
  void recallAtKOverAnEmptyRankingIsZero() {
    assertThat(RetrievalMetrics.recallAtK(List.of(), Set.of("a"), 5)).isZero();
  }

  @Test
  void recallAtKRejectsAnEmptyRelevantSet() {
    assertThatThrownBy(() -> RetrievalMetrics.recallAtK(ranked, Set.of(), 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reciprocalRankAndFirstRelevantRank() {
    assertThat(RetrievalMetrics.reciprocalRank(List.of("a", "b", "c"), Set.of("c")))
        .isCloseTo(1.0 / 3, within(1e-9));
    assertThat(RetrievalMetrics.reciprocalRank(List.of("a", "b"), Set.of("z"))).isZero();
    assertThat(RetrievalMetrics.firstRelevantRank(List.of("a", "b", "c"), Set.of("b")))
        .isEqualTo(2);
    assertThat(RetrievalMetrics.firstRelevantRank(List.of("a"), Set.of("z"))).isEqualTo(-1);
  }

  @Test
  void mrrIsTheMeanOfPerQueryReciprocalRanks() {
    assertThat(RetrievalMetrics.mrr(List.of(1.0, 0.5, 0.0))).isCloseTo(0.5, within(1e-9));
    assertThat(RetrievalMetrics.mrr(List.of())).isZero();
  }

  @Test
  void ndcgIsOneForAPerfectRankingAndLessForAWorseOne() {
    Map<String, Integer> grades = Map.of("a", 2, "b", 1);
    assertThat(RetrievalMetrics.ndcgAtK(List.of("a", "b", "c", "d"), grades, 10))
        .isCloseTo(1.0, within(1e-9));
    double reversed = RetrievalMetrics.ndcgAtK(List.of("c", "d", "b", "a"), grades, 10);
    assertThat(reversed).isGreaterThan(0.0).isLessThan(1.0);
  }

  @Test
  void ndcgMatchesAHandComputedExample() {
    // ranking [b, a]; grades a=2 (gain 3), b=1 (gain 1)
    // DCG  = 1/log2(2) + 3/log2(3) = 1.0 + 1.8928 = 2.8928
    // IDCG = 3/log2(2) + 1/log2(3) = 3.0 + 0.6309 = 3.6309
    assertThat(RetrievalMetrics.ndcgAtK(List.of("b", "a"), Map.of("a", 2, "b", 1), 10))
        .isCloseTo(2.8928 / 3.6309, within(1e-3));
  }

  @Test
  void ndcgRejectsAnEmptyGradeMap() {
    assertThatThrownBy(() -> RetrievalMetrics.ndcgAtK(ranked, Map.of(), 5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
