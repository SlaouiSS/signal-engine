package org.signalengine.rag.evaluation;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.context.ContextPassage;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.generation.RagAnswer;
import org.signalengine.rag.provenance.Citation;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.signalengine.rag.retrieval.RetrievedPassage;

/**
 * Deterministic hand-built {@link RagExecution}s for the evaluator tests. No pipeline, no doubles.
 */
final class RagExecutionFixtures {

  private RagExecutionFixtures() {}

  static Provenance provenance(String passageId) {
    return new Provenance(
        "src-" + passageId,
        URI.create("https://example.test/" + passageId),
        "Title of " + passageId,
        "doc-" + passageId,
        passageId,
        Map.of("author", "A. Writer"));
  }

  static RetrievedPassage retrieved(String passageId, double score) {
    return new RetrievedPassage(
        passageId, "text of " + passageId, provenance(passageId), score, Map.of());
  }

  static ContextPassage contextPassage(String passageId) {
    return new ContextPassage(passageId, "text of " + passageId, provenance(passageId), Map.of());
  }

  static Citation citation(String passageId) {
    return new Citation(passageId, provenance(passageId), null);
  }

  /**
   * Retrieval returns {@code retrievedIds} in order; context mirrors them; the answer cites {@code
   * citedIds}.
   */
  static RagExecution execution(
      String queryText,
      List<String> retrievedIds,
      List<String> contextIds,
      boolean answered,
      List<String> citedIds) {

    RagAnswer answer =
        answered
            ? RagAnswer.answered(
                "an answer", citedIds.stream().map(RagExecutionFixtures::citation).toList())
            : RagAnswer.insufficientEvidence("the context does not support an answer");
    return execution(queryText, retrievedIds, contextIds, answer);
  }

  static RagExecution execution(
      String queryText, List<String> retrievedIds, List<String> contextIds, RagAnswer answer) {
    Query query = Query.of(queryText);
    RetrievalResult retrieval =
        new RetrievalResult(
            retrievedIds.stream()
                .map(id -> retrieved(id, 1.0 - retrievedIds.indexOf(id) * 0.1))
                .toList(),
            Map.of("strategy", "fixture"));
    Context context =
        new Context(
            contextIds.stream().map(RagExecutionFixtures::contextPassage).toList(),
            Map.of("assembler", "fixture"));
    return new RagExecution(
        UUID.randomUUID(),
        query,
        query,
        retrieval,
        context,
        answer,
        List.of(),
        Instant.EPOCH,
        Instant.EPOCH.plusSeconds(1),
        Map.of());
  }

  /** An execution with no generator run (null answer). */
  static RagExecution withoutAnswer(String queryText, List<String> retrievedIds) {
    return execution(queryText, retrievedIds, retrievedIds, (RagAnswer) null);
  }
}
