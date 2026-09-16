package org.signalengine.rag.evaluation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A small, explicit, versioned set of {@link RelevanceJudgement}s a {@link RagExecutionEvaluator}
 * scores executions against (docs/09-evaluation.md Section 14; docs/adr/0016-rag-evaluation.md).
 *
 * <p>This type is <b>only the shape</b>: an in-memory list plus a {@code version} string. It reads
 * no file and parses no format &mdash; the RAG core stays framework-free. How a dataset is
 * authored, stored, and loaded (a JSON fixture, a database table, a shared golden set) is an
 * <b>open decision</b> (docs/adr/0016 &sect; Open decisions); the tests build one from a versioned
 * JSON fixture through a test-only loader.
 *
 * <p>A dataset is never production data: it is hand-authored and checked in, so evaluation is
 * reproducible and independent of what happens to be in the database.
 *
 * @param version identifier of this dataset revision (e.g. {@code "2026-09-08"}); never blank
 * @param judgements the judgements, one per distinct query text; never {@code null}
 */
public record RagEvaluationDataset(String version, List<RelevanceJudgement> judgements) {

  public RagEvaluationDataset {
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
    judgements = judgements == null ? List.of() : List.copyOf(judgements);
    Map<String, RelevanceJudgement> byQuery = new LinkedHashMap<>();
    for (RelevanceJudgement judgement : judgements) {
      if (judgement == null) {
        throw new IllegalArgumentException("judgements must not contain null");
      }
      if (byQuery.putIfAbsent(judgement.queryText(), judgement) != null) {
        throw new IllegalArgumentException(
            "duplicate judgement for query: " + judgement.queryText());
      }
    }
  }

  /** The judgement for this exact query text, if the dataset has one. */
  public Optional<RelevanceJudgement> judgementFor(String queryText) {
    return judgements.stream()
        .filter(judgement -> judgement.queryText().equals(queryText))
        .findFirst();
  }

  public int size() {
    return judgements.size();
  }
}
