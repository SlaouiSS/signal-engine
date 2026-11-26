package org.signalengine.application.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.signalengine.application.dedup.GroupIntoRelevantInformationUseCase;
import org.signalengine.application.dedup.GroupingReport;
import org.signalengine.application.ingestion.CollectFromSourceUseCase;
import org.signalengine.application.signal.IndexReconciliationReport;
import org.signalengine.application.signal.ProcessRelevantInformationUseCase;
import org.signalengine.application.signal.ProcessingReport;
import org.signalengine.application.signal.ReconcileKnowledgeBaseIndexUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link RunPipelineCycleUseCase}.
 *
 * <p>Each stage has its own {@link StageBudget}, so a slow stage cannot starve the next. A stage
 * that works on a backlog takes further batches while its budget lasts, up to a maximum number, and
 * stops as soon as a batch moves nothing forward.
 */
public final class DefaultRunPipelineCycleUseCase implements RunPipelineCycleUseCase {

  private static final Logger log = LoggerFactory.getLogger(DefaultRunPipelineCycleUseCase.class);

  private final CollectFromSourceUseCase collectFromSource;
  private final GroupIntoRelevantInformationUseCase groupIntoRelevantInformation;
  private final ProcessRelevantInformationUseCase processRelevantInformation;
  private final ReconcileKnowledgeBaseIndexUseCase reconcileKnowledgeBaseIndex;
  private final Clock clock;
  private final Duration stageBudget;
  private final int maxBatchesPerStage;

  public DefaultRunPipelineCycleUseCase(
      CollectFromSourceUseCase collectFromSource,
      GroupIntoRelevantInformationUseCase groupIntoRelevantInformation,
      ProcessRelevantInformationUseCase processRelevantInformation,
      ReconcileKnowledgeBaseIndexUseCase reconcileKnowledgeBaseIndex,
      Clock clock,
      Duration stageBudget,
      int maxBatchesPerStage) {
    this.collectFromSource = collectFromSource;
    this.groupIntoRelevantInformation = groupIntoRelevantInformation;
    this.processRelevantInformation = processRelevantInformation;
    this.reconcileKnowledgeBaseIndex = reconcileKnowledgeBaseIndex;
    this.clock = clock;
    this.stageBudget = stageBudget;
    this.maxBatchesPerStage = maxBatchesPerStage;
  }

  @Override
  public List<StageReport> runCycle() {
    return List.of(
        // One pass over the sources: collecting them again in the same cycle would find nothing
        // new.
        runStage(
            "collection",
            1,
            budget ->
                collectFromSource.collectFromEnabledSources(budget).stream()
                    .map(report -> report.status().name())
                    .toList()),
        runStage(
            "grouping",
            maxBatchesPerStage,
            budget ->
                groupIntoRelevantInformation.groupNormalizedRawInformationItems(budget).stream()
                    .map(GroupingReport::outcome)
                    .map(Enum::name)
                    .toList()),
        runStage(
            "processing",
            maxBatchesPerStage,
            budget ->
                processRelevantInformation.processPending(budget).stream()
                    .map(ProcessingReport::outcome)
                    .map(Enum::name)
                    .toList()),
        runStage(
            "knowledge_base_reconciliation",
            maxBatchesPerStage,
            budget ->
                reconcileKnowledgeBaseIndex.reconcilePending(budget).stream()
                    .map(IndexReconciliationReport::outcome)
                    .map(Enum::name)
                    .toList()));
  }

  private StageReport runStage(
      String stage, int maxBatches, Function<StageBudget, List<String>> batch) {
    Instant startedAt = clock.instant();
    StageBudget budget = new StageBudget(startedAt.plus(stageBudget), clock);
    Map<String, Integer> outcomes = new LinkedHashMap<>();
    boolean failed = false;
    try {
      for (int taken = 0; taken < maxBatches && !budget.exhausted(); taken++) {
        List<String> batchOutcomes = batch.apply(budget);
        batchOutcomes.forEach(outcome -> outcomes.merge(outcome, 1, Integer::sum));
        if (batchOutcomes.stream().noneMatch(DefaultRunPipelineCycleUseCase::movedForward)) {
          break;
        }
      }
    } catch (RuntimeException failure) {
      failed = true;
      log.error("Pipeline stage {} failed", stage, failure);
    }
    return new StageReport(stage, Duration.between(startedAt, clock.instant()), outcomes, failed);
  }

  /** Skipped and suspended work is still waiting, so another batch would only find it again. */
  private static boolean movedForward(String outcome) {
    return !outcome.startsWith("SKIPPED") && !outcome.equals("SUSPENDED");
  }
}
