package org.signalengine.infrastructure.rag.retrieval;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retrieval-quality metrics over a ranked list of passage ids and human-authored relevance labels.
 * The Java counterpart of {@code agents/benchmarks/embedding/metrics.py} (Task 8.3A), so the 8.3C
 * end-to-end retrieval benchmark is comparable to the 8.3A embedding-only benchmark. No composite
 * score (docs/09-evaluation.md Section 10). Test-only.
 */
final class RetrievalMetrics {

  private RetrievalMetrics() {}

  /** Fraction of a query's relevant passages found in the top {@code k}. */
  static double recallAtK(List<String> rankedIds, Set<String> relevantIds, int k) {
    if (relevantIds.isEmpty()) {
      throw new IllegalArgumentException("recall is undefined with no relevant passages");
    }
    long hits = rankedIds.stream().limit(k).filter(relevantIds::contains).distinct().count();
    return (double) hits / relevantIds.size();
  }

  /** 1 / rank of the first relevant passage, or 0 if none is retrieved. */
  static double reciprocalRank(List<String> rankedIds, Set<String> relevantIds) {
    for (int i = 0; i < rankedIds.size(); i++) {
      if (relevantIds.contains(rankedIds.get(i))) {
        return 1.0 / (i + 1);
      }
    }
    return 0.0;
  }

  static double mrr(List<Double> reciprocalRanks) {
    if (reciprocalRanks.isEmpty()) {
      return 0.0;
    }
    return reciprocalRanks.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
  }

  /** nDCG@k with graded relevance, gain = {@code 2^grade - 1}. */
  static double ndcgAtK(List<String> rankedIds, Map<String, Integer> idToGrade, int k) {
    if (idToGrade.isEmpty()) {
      throw new IllegalArgumentException("nDCG is undefined with no graded passages");
    }
    double dcg = 0.0;
    for (int i = 0; i < Math.min(k, rankedIds.size()); i++) {
      int grade = idToGrade.getOrDefault(rankedIds.get(i), 0);
      dcg += (Math.pow(2, grade) - 1) / (Math.log(i + 2) / Math.log(2));
    }
    double idealDcg = 0.0;
    List<Integer> idealGrades =
        idToGrade.values().stream().sorted((a, b) -> Integer.compare(b, a)).limit(k).toList();
    for (int i = 0; i < idealGrades.size(); i++) {
      idealDcg += (Math.pow(2, idealGrades.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
    }
    return idealDcg > 0 ? dcg / idealDcg : 0.0;
  }
}
