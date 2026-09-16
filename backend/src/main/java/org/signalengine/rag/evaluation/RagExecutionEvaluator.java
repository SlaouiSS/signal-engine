package org.signalengine.rag.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.context.ContextPassage;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.generation.RagAnswer;
import org.signalengine.rag.provenance.Citation;
import org.signalengine.rag.retrieval.RetrievedPassage;

/**
 * The supplied {@link Evaluator}: a deterministic, after-the-fact assessment of one {@link
 * RagExecution} (docs/09-evaluation.md Section 4, 11; docs/adr/0016-rag-evaluation.md).
 *
 * <p>It is <b>outside</b> the runtime pipeline &mdash; it takes only the finished, immutable
 * execution and a hand-authored {@link RagEvaluationDataset}, computes numbers, and returns an
 * {@link EvaluationResult}. It never re-runs retrieval or generation, touches no database, calls no
 * model, and makes no LLM-judge call. Running it twice on the same inputs yields an equal result.
 *
 * <p>What it assesses, from data already on the execution:
 *
 * <ul>
 *   <li><b>Retrieval quality</b> &mdash; if the dataset judges this query: {@code recall@1/3/5/10},
 *       {@code reciprocalRank}, {@code ndcg@10} of the retrieved ranking against the judged
 *       relevant passages.
 *   <li><b>Answerability behaviour</b> &mdash; if the dataset judges this query: whether the system
 *       answered / abstained as expected ({@code answerabilityAgreement}); answering a query judged
 *       unanswerable is also flagged as a grounding risk.
 *   <li><b>Grounding / citation validity</b> &mdash; always, structurally against the execution's
 *       own context: {@code citationValidity} (fraction of the answer's citations that resolve to a
 *       supplied context passage); an unresolved or provenance-mismatched citation, and an answered
 *       response with no citation, are flagged.
 *   <li><b>Execution outcome</b> &mdash; notes when the run cannot be meaningfully evaluated (no
 *       answer generated, empty retrieval, no dataset judgement).
 * </ul>
 *
 * <p>No dimension is combined into an overall score.
 */
public final class RagExecutionEvaluator implements Evaluator {

  public static final String IMPLEMENTATION_ID = "rag-execution-evaluator";

  private static final int[] RECALL_K = {1, 3, 5, 10};
  private static final int NDCG_K = 10;

  private final RagEvaluationDataset dataset;
  private final ComponentDescriptor descriptor;

  public RagExecutionEvaluator(RagEvaluationDataset dataset) {
    this.dataset = Objects.requireNonNull(dataset, "dataset must not be null");
    this.descriptor =
        new ComponentDescriptor(
            RagComponentType.EVALUATOR,
            IMPLEMENTATION_ID,
            "deterministic/v1;dataset=" + dataset.version());
  }

  @Override
  public ComponentDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public EvaluationResult evaluate(RagExecution execution) {
    Objects.requireNonNull(execution, "execution must not be null");

    List<EvaluationFinding> findings = new ArrayList<>();
    List<EvaluationMetric> metrics = new ArrayList<>();

    List<String> retrievedIds =
        execution.retrieval().passages().stream().map(RetrievedPassage::passageId).toList();
    if (retrievedIds.isEmpty()) {
      findings.add(
          EvaluationFinding.of(
              EvaluationDimension.EXECUTION_OUTCOME,
              "retrieval returned no passages; retrieval and grounding metrics are limited"));
    }
    if (execution.context().isEmpty() && !retrievedIds.isEmpty()) {
      findings.add(
          EvaluationFinding.of(
              EvaluationDimension.CONTEXT_QUALITY,
              "retrieval returned passages but the assembled context is empty"));
    }

    Optional<RelevanceJudgement> judgement = dataset.judgementFor(execution.originalQuery().text());
    if (judgement.isPresent()) {
      evaluateRetrieval(retrievedIds, judgement.get(), findings, metrics);
      evaluateAnswerability(execution.answer(), judgement.get(), findings, metrics);
    } else {
      findings.add(
          EvaluationFinding.of(
              EvaluationDimension.EXECUTION_OUTCOME,
              "no judgement in dataset '"
                  + dataset.version()
                  + "' for this query; retrieval quality and answerability were not scored"));
    }

    evaluateGrounding(execution, findings, metrics);

    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("datasetVersion", dataset.version());
    metadata.put("judged", Boolean.toString(judgement.isPresent()));
    metadata.put("retrievedPassages", Integer.toString(retrievedIds.size()));
    metadata.put("contextPassages", Integer.toString(execution.context().passages().size()));
    metadata.put("generated", Boolean.toString(execution.answer() != null));
    return new EvaluationResult(execution.executionId(), descriptor, findings, metrics, metadata);
  }

  private void evaluateRetrieval(
      List<String> retrievedIds,
      RelevanceJudgement judgement,
      List<EvaluationFinding> findings,
      List<EvaluationMetric> metrics) {

    Set<String> relevantIds = judgement.relevantPassageIds();
    if (relevantIds.isEmpty()) {
      findings.add(
          EvaluationFinding.of(
              EvaluationDimension.RETRIEVAL_QUALITY,
              "query judged to have no relevant passages; ranking metrics are not applicable"));
      return;
    }

    Map<String, String> shared =
        Map.of(
            "retrieved", Integer.toString(retrievedIds.size()),
            "relevant", Integer.toString(relevantIds.size()));
    for (int k : RECALL_K) {
      metrics.add(
          new EvaluationMetric(
              EvaluationDimension.RETRIEVAL_QUALITY,
              "recall@" + k,
              RetrievalMetrics.recallAtK(retrievedIds, relevantIds, k),
              withEntry(shared, "k", Integer.toString(k))));
    }

    int firstRelevantRank = RetrievalMetrics.firstRelevantRank(retrievedIds, relevantIds);
    metrics.add(
        new EvaluationMetric(
            EvaluationDimension.RETRIEVAL_QUALITY,
            "reciprocalRank",
            RetrievalMetrics.reciprocalRank(retrievedIds, relevantIds),
            withEntry(
                shared,
                "firstRelevantRank",
                firstRelevantRank < 0 ? "none" : Integer.toString(firstRelevantRank))));

    metrics.add(
        new EvaluationMetric(
            EvaluationDimension.RETRIEVAL_QUALITY,
            "ndcg@" + NDCG_K,
            RetrievalMetrics.ndcgAtK(retrievedIds, judgement.relevantPassageGrades(), NDCG_K),
            shared));

    findings.add(
        new EvaluationFinding(
            EvaluationDimension.RETRIEVAL_QUALITY,
            "retrieval ranking scored against the dataset judgement",
            Map.of(
                "rankedPassageIds", String.join(",", retrievedIds),
                "relevantPassageIds", String.join(",", sorted(relevantIds)),
                "firstRelevantRank",
                    firstRelevantRank < 0 ? "none" : Integer.toString(firstRelevantRank))));
  }

  private void evaluateAnswerability(
      RagAnswer answer,
      RelevanceJudgement judgement,
      List<EvaluationFinding> findings,
      List<EvaluationMetric> metrics) {

    if (answer == null) {
      findings.add(
          EvaluationFinding.of(
              EvaluationDimension.EXECUTION_OUTCOME,
              "no answer in this execution; answerability behaviour was not scored"));
      return;
    }

    boolean expectedAnswerable = judgement.answerable();
    boolean systemAnswered = answer.answered();
    boolean agrees = expectedAnswerable == systemAnswered;

    metrics.add(
        new EvaluationMetric(
            EvaluationDimension.ANSWER_RELEVANCE,
            "answerabilityAgreement",
            agrees ? 1.0 : 0.0,
            Map.of(
                "expectedAnswerable", Boolean.toString(expectedAnswerable),
                "systemAnswered", Boolean.toString(systemAnswered))));

    if (!expectedAnswerable && systemAnswered) {
      findings.add(
          EvaluationFinding.of(
              EvaluationDimension.GROUNDING_FAITHFULNESS,
              "the system produced an answer for a query judged unanswerable from the corpus"
                  + " — a possible ungrounded answer"));
    } else if (expectedAnswerable && !systemAnswered) {
      findings.add(
          EvaluationFinding.of(
              EvaluationDimension.ANSWER_RELEVANCE,
              "the system abstained on a query judged answerable from the corpus"));
    }
  }

  private void evaluateGrounding(
      RagExecution execution, List<EvaluationFinding> findings, List<EvaluationMetric> metrics) {

    RagAnswer answer = execution.answer();
    if (answer == null) {
      return; // already noted under EXECUTION_OUTCOME
    }

    Map<String, ContextPassage> contextById = new LinkedHashMap<>();
    for (ContextPassage passage : execution.context().passages()) {
      contextById.put(passage.passageId(), passage);
    }

    List<Citation> citations = answer.citations();
    int resolved = 0;
    List<String> unresolved = new ArrayList<>();
    List<String> provenanceMismatch = new ArrayList<>();
    for (Citation citation : citations) {
      ContextPassage passage = contextById.get(citation.passageId());
      if (passage == null) {
        unresolved.add(citation.passageId());
      } else if (!passage.provenance().equals(citation.provenance())) {
        provenanceMismatch.add(citation.passageId());
      } else {
        resolved++;
      }
    }

    if (!citations.isEmpty()) {
      metrics.add(
          new EvaluationMetric(
              EvaluationDimension.CITATION_CORRECTNESS,
              "citationValidity",
              (double) resolved / citations.size(),
              Map.of(
                  "citations", Integer.toString(citations.size()),
                  "resolved", Integer.toString(resolved),
                  "unresolved", Integer.toString(unresolved.size()),
                  "provenanceMismatch", Integer.toString(provenanceMismatch.size()))));
    }

    if (!unresolved.isEmpty()) {
      findings.add(
          new EvaluationFinding(
              EvaluationDimension.GROUNDING_FAITHFULNESS,
              "the answer cites passages that are not in the supplied context",
              Map.of("unknownPassageIds", String.join(",", sorted(Set.copyOf(unresolved))))));
    }
    if (!provenanceMismatch.isEmpty()) {
      findings.add(
          new EvaluationFinding(
              EvaluationDimension.CITATION_CORRECTNESS,
              "a citation references a context passage but carries different provenance",
              Map.of("passageIds", String.join(",", sorted(Set.copyOf(provenanceMismatch))))));
    }
    if (answer.answered() && citations.isEmpty()) {
      findings.add(
          EvaluationFinding.of(
              EvaluationDimension.CITATION_CORRECTNESS,
              "the answer is marked grounded but cites no passage"));
      metrics.add(
          EvaluationMetric.of(EvaluationDimension.CITATION_CORRECTNESS, "citationValidity", 0.0));
    }
    if (!answer.answered() && !citations.isEmpty()) {
      findings.add(
          EvaluationFinding.of(
              EvaluationDimension.CITATION_CORRECTNESS,
              "an insufficient-evidence answer carries citations"));
    }
  }

  private static List<String> sorted(Set<String> values) {
    return values.stream().sorted().toList();
  }

  private static Map<String, String> withEntry(Map<String, String> base, String key, String value) {
    Map<String, String> copy = new LinkedHashMap<>(base);
    copy.put(key, value);
    return copy;
  }
}
