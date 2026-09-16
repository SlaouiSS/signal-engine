package org.signalengine.rag.evaluation;

import java.util.Map;
import java.util.Set;

/**
 * A human-authored judgement about one query: which stored passages are relevant to it (with an
 * optional graded relevance) and whether the query is expected to be answerable from the corpus at
 * all (docs/09-evaluation.md Section 11, 14; docs/adr/0016-rag-evaluation.md).
 *
 * <p>Passage ids &mdash; not content ids &mdash; because that is what a {@link
 * org.signalengine.rag.retrieval.RetrievalResult} ranks. Grades follow the Task 8.3A convention
 * ({@code 2} = directly relevant, {@code 1} = partially relevant); a caller that only knows
 * "relevant / not" uses grade {@code 1} throughout. An unanswerable query normally has an empty
 * relevant set, but the record does not force that &mdash; the two facts are recorded
 * independently.
 *
 * @param queryText the query text this judges, matched verbatim against {@link
 *     org.signalengine.rag.execution.RagExecution#originalQuery()}; never blank
 * @param relevantPassageGrades passage id &rarr; relevance grade ({@code >= 1}); never {@code
 *     null}, keys never blank
 * @param answerable whether a correct system answers this from the corpus (vs. explicitly
 *     abstaining)
 */
public record RelevanceJudgement(
    String queryText, Map<String, Integer> relevantPassageGrades, boolean answerable) {

  public RelevanceJudgement {
    if (queryText == null || queryText.isBlank()) {
      throw new IllegalArgumentException("queryText must not be blank");
    }
    relevantPassageGrades =
        relevantPassageGrades == null ? Map.of() : Map.copyOf(relevantPassageGrades);
    relevantPassageGrades.forEach(
        (passageId, grade) -> {
          if (passageId == null || passageId.isBlank()) {
            throw new IllegalArgumentException("relevant passage ids must not be blank");
          }
          if (grade == null || grade < 1) {
            throw new IllegalArgumentException(
                "relevance grade for '" + passageId + "' must be >= 1, was " + grade);
          }
        });
  }

  /** The set of passage ids judged relevant (any grade). */
  public Set<String> relevantPassageIds() {
    return relevantPassageGrades.keySet();
  }

  /** An answerable query with every listed passage at grade 1. */
  public static RelevanceJudgement answerable(String queryText, Set<String> relevantPassageIds) {
    return new RelevanceJudgement(
        queryText,
        relevantPassageIds.stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(passageId -> passageId, id -> 1)),
        true);
  }

  /** A query the corpus cannot answer &mdash; the system is expected to abstain. */
  public static RelevanceJudgement unanswerable(String queryText) {
    return new RelevanceJudgement(queryText, Map.of(), false);
  }
}
