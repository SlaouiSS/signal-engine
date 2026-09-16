package org.signalengine.rag.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic ranking metrics over a ranked list of passage ids and a relevance judgement. Pure
 * functions, no state.
 *
 * <p>Same definitions as the Task 8.3A/8.3C retrieval benchmark ({@code
 * agents/benchmarks/embedding/metrics.py} and its Java counterpart) so a number from the evaluator
 * is comparable to a benchmark number. Kept here, package-private, because only {@link
 * RagExecutionEvaluator} needs it; the benchmark keeps its own test-only copy.
 */
final class RetrievalMetrics {

  private RetrievalMetrics() {}

  /** Fraction of the judged-relevant passages that appear in the top {@code k} of the ranking. */
  static double recallAtK(List<String> rankedIds, Set<String> relevantIds, int k) {
    if (relevantIds.isEmpty()) {
      throw new IllegalArgumentException("recall is undefined with no relevant passages");
    }
    long hits = rankedIds.stream().limit(k).filter(relevantIds::contains).distinct().count();
    return (double) hits / relevantIds.size();
  }

  /** {@code 1 / rank} of the first relevant passage in the ranking, or {@code 0} if none is. */
  static double reciprocalRank(List<String> rankedIds, Set<String> relevantIds) {
    int rank = firstRelevantRank(rankedIds, relevantIds);
    return rank < 0 ? 0.0 : 1.0 / rank;
  }

  /** 1-based position of the first relevant passage, or {@code -1} if none is retrieved. */
  static int firstRelevantRank(List<String> rankedIds, Set<String> relevantIds) {
    for (int i = 0; i < rankedIds.size(); i++) {
      if (relevantIds.contains(rankedIds.get(i))) {
        return i + 1;
      }
    }
    return -1;
  }

  /** Mean of a set of per-query reciprocal ranks (MRR); {@code 0} for an empty list. */
  static double mrr(List<Double> reciprocalRanks) {
    return reciprocalRanks.isEmpty()
        ? 0.0
        : reciprocalRanks.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
  }

  /** nDCG@k with graded relevance, gain {@code 2^grade - 1}, discount {@code log2(rank + 1)}. */
  static double ndcgAtK(List<String> rankedIds, Map<String, Integer> idToGrade, int k) {
    if (idToGrade.isEmpty()) {
      throw new IllegalArgumentException("nDCG is undefined with no graded passages");
    }
    double dcg = 0.0;
    for (int i = 0; i < Math.min(k, rankedIds.size()); i++) {
      int grade = idToGrade.getOrDefault(rankedIds.get(i), 0);
      dcg += gain(grade) / discount(i);
    }
    double idealDcg = 0.0;
    List<Integer> idealGrades =
        idToGrade.values().stream().sorted((a, b) -> Integer.compare(b, a)).limit(k).toList();
    for (int i = 0; i < idealGrades.size(); i++) {
      idealDcg += gain(idealGrades.get(i)) / discount(i);
    }
    return idealDcg > 0 ? dcg / idealDcg : 0.0;
  }

  private static double gain(int grade) {
    return Math.pow(2, grade) - 1;
  }

  private static double discount(int zeroBasedRank) {
    return Math.log(zeroBasedRank + 2) / Math.log(2);
  }
}
