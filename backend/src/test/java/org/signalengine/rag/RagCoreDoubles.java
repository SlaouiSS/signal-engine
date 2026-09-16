package org.signalengine.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.context.ContextAssembler;
import org.signalengine.rag.context.ContextPassage;
import org.signalengine.rag.context.ContextRefiner;
import org.signalengine.rag.evaluation.EvaluationDimension;
import org.signalengine.rag.evaluation.EvaluationFinding;
import org.signalengine.rag.evaluation.EvaluationResult;
import org.signalengine.rag.evaluation.Evaluator;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.generation.Generator;
import org.signalengine.rag.generation.RagAnswer;
import org.signalengine.rag.provenance.Citation;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.query.QueryProcessor;
import org.signalengine.rag.retrieval.Reranker;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.signalengine.rag.retrieval.RetrievedPassage;
import org.signalengine.rag.retrieval.Retriever;

/**
 * Deterministic, dependency-free test doubles for the RAG core contracts. No LLM, no database, no
 * external service &mdash; each double does one obvious, predictable thing so composition behaviour
 * can be asserted exactly.
 */
final class RagCoreDoubles {

  private RagCoreDoubles() {}

  static Provenance provenance(String sourceId, String passageId) {
    return new Provenance(
        sourceId, null, "Title of " + passageId, "doc-" + sourceId, passageId, Map.of());
  }

  static RetrievedPassage passage(String passageId, String text, double score) {
    return RetrievedPassage.of(passageId, text, provenance("acme", passageId), score);
  }

  /** Returns a fixed retrieval result and counts how many times it was asked. */
  static final class FixedRetriever implements Retriever {

    private final RetrievalResult result;
    int calls;

    FixedRetriever(RetrievedPassage... passages) {
      this.result = new RetrievalResult(List.of(passages), Map.of("strategy", "fixed"));
    }

    @Override
    public RetrievalResult retrieve(Query query) {
      calls++;
      return result;
    }

    @Override
    public ComponentDescriptor descriptor() {
      return new ComponentDescriptor(RagComponentType.RETRIEVER, "fixed-retriever", "test");
    }
  }

  /** Maps every retrieved passage to a context passage 1:1, preserving order, id and provenance. */
  static final class OrderPreservingContextAssembler implements ContextAssembler {

    @Override
    public Context assemble(Query query, RetrievalResult retrieved) {
      List<ContextPassage> passages = new ArrayList<>();
      for (RetrievedPassage passage : retrieved.passages()) {
        passages.add(
            new ContextPassage(
                passage.passageId(), passage.text(), passage.provenance(), passage.metadata()));
      }
      return new Context(passages, Map.of("assembler", "order-preserving"));
    }

    @Override
    public ComponentDescriptor descriptor() {
      return new ComponentDescriptor(
          RagComponentType.CONTEXT_ASSEMBLER, "order-preserving-assembler", "test");
    }
  }

  /** Answers with fixed text, citing the first context passage. Counts invocations. */
  static final class FirstPassageGenerator implements Generator {

    int calls;

    @Override
    public RagAnswer generate(Query query, Context context) {
      calls++;
      if (context.isEmpty()) {
        return RagAnswer.insufficientEvidence("no passages in context");
      }
      ContextPassage first = context.passages().get(0);
      return RagAnswer.answered(
          "grounded answer",
          List.of(new Citation(first.passageId(), first.provenance(), first.text())));
    }

    @Override
    public ComponentDescriptor descriptor() {
      return new ComponentDescriptor(RagComponentType.GENERATOR, "first-passage-generator", "test");
    }
  }

  /**
   * A generator that always fails, to prove {@code execute} propagates a {@link
   * GenerationException}.
   */
  static final class FailingGenerator implements Generator {

    @Override
    public RagAnswer generate(Query query, Context context) {
      throw new org.signalengine.rag.generation.GenerationException("provider unavailable in test");
    }

    @Override
    public ComponentDescriptor descriptor() {
      return new ComponentDescriptor(RagComponentType.GENERATOR, "failing-generator", "test");
    }
  }

  /**
   * Appends a marker token to the query text (proves a query stage ran without touching others).
   */
  static final class MarkerQueryProcessor implements QueryProcessor {

    static final String MARKER = " [processed]";

    @Override
    public Query process(Query query) {
      return query.withText(query.text() + MARKER);
    }

    @Override
    public ComponentDescriptor descriptor() {
      return new ComponentDescriptor(
          RagComponentType.QUERY_PROCESSOR, "marker-query-processor", "test");
    }
  }

  /**
   * Reverses passage order (proves a reranker was inserted without changing retrieval/assembly).
   */
  static final class ReversingReranker implements Reranker {

    @Override
    public RetrievalResult rerank(Query query, RetrievalResult retrieved) {
      List<RetrievedPassage> reversed = new ArrayList<>(retrieved.passages());
      java.util.Collections.reverse(reversed);
      return new RetrievalResult(reversed, retrieved.metadata());
    }

    @Override
    public ComponentDescriptor descriptor() {
      return new ComponentDescriptor(RagComponentType.RERANKER, "reversing-reranker", "test");
    }
  }

  /** Keeps only the first passage of the context (proves a post-assembly stage was inserted). */
  static final class HeadContextRefiner implements ContextRefiner {

    @Override
    public Context refine(Query query, Context context) {
      if (context.passages().size() <= 1) {
        return context;
      }
      return new Context(List.of(context.passages().get(0)), context.metadata());
    }

    @Override
    public ComponentDescriptor descriptor() {
      return new ComponentDescriptor(
          RagComponentType.CONTEXT_REFINER, "head-context-refiner", "test");
    }
  }

  /** Records the execution it was given and reports one finding per dimension; never mutates. */
  static final class RecordingEvaluator implements Evaluator {

    RagExecution seen;

    @Override
    public EvaluationResult evaluate(RagExecution execution) {
      this.seen = execution;
      List<EvaluationFinding> findings = new ArrayList<>();
      findings.add(
          EvaluationFinding.of(
              EvaluationDimension.RETRIEVAL_QUALITY,
              "retrieved " + execution.retrieval().passages().size() + " passages"));
      findings.add(
          EvaluationFinding.of(
              EvaluationDimension.CONTEXT_QUALITY,
              "context holds " + execution.context().passages().size() + " passages"));
      return new EvaluationResult(
          execution.executionId(), descriptor(), findings, List.of(), Map.of());
    }

    @Override
    public ComponentDescriptor descriptor() {
      return new ComponentDescriptor(RagComponentType.EVALUATOR, "recording-evaluator", "test");
    }
  }
}
