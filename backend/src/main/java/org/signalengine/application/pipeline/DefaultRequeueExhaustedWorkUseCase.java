package org.signalengine.application.pipeline;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.application.signal.BlockedIndexing;
import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.domain.ActivityRecord;
import org.signalengine.domain.RawInformationItem;

/** Default implementation of {@link RequeueExhaustedWorkUseCase}. */
public final class DefaultRequeueExhaustedWorkUseCase implements RequeueExhaustedWorkUseCase {

  private static final String ACTIVITY_CATEGORY = "operations";

  private final RawInformationItemRepository rawInformationItemRepository;
  private final KnowledgeBaseIndexer knowledgeBaseIndexer;
  private final ActivityRecordRepository activityRecordRepository;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public DefaultRequeueExhaustedWorkUseCase(
      RawInformationItemRepository rawInformationItemRepository,
      KnowledgeBaseIndexer knowledgeBaseIndexer,
      ActivityRecordRepository activityRecordRepository,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.rawInformationItemRepository = rawInformationItemRepository;
    this.knowledgeBaseIndexer = knowledgeBaseIndexer;
    this.activityRecordRepository = activityRecordRepository;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  @Override
  public List<ExhaustedWork> findExhaustedWork() {
    List<ExhaustedWork> exhausted = new ArrayList<>();
    rawInformationItemRepository.findRetryableFailures().stream()
        .map(DefaultRequeueExhaustedWorkUseCase::describe)
        .forEach(exhausted::add);
    knowledgeBaseIndexer.findBlockedIndexing().stream()
        .map(DefaultRequeueExhaustedWorkUseCase::describe)
        .forEach(exhausted::add);
    return List.copyOf(exhausted);
  }

  @Override
  public List<ExhaustedWork> requeueRecoverableWork() {
    List<ExhaustedWork> requeued = new ArrayList<>();
    for (RawInformationItem item : rawInformationItemRepository.findRetryableFailures()) {
      if (requeueItem(item)) {
        requeued.add(describe(item));
      }
    }
    requeued.addAll(requeueIndexing());
    return List.copyOf(requeued);
  }

  private boolean requeueItem(RawInformationItem item) {
    PipelineStage stage = PipelineStage.named(item.processingState().failedStage());
    return unitOfWork.inTransaction(
        () -> {
          boolean moved =
              rawInformationItemRepository.requeueRetryableFailure(
                  item.id(), stage.stageName(), stage.waitingState(), clock.instant());
          if (moved) {
            recordRequeue(item.id(), stage.stageName(), item.processingState().failureReason());
          }
          return moved;
        });
  }

  private List<ExhaustedWork> requeueIndexing() {
    return unitOfWork.inTransaction(
        () -> {
          List<BlockedIndexing> blocked = knowledgeBaseIndexer.findBlockedIndexing();
          List<UUID> requeuedIds = knowledgeBaseIndexer.requeueBlockedIndexing();
          requeuedIds.forEach(
              id -> recordRequeue(id, ExhaustedWork.KNOWLEDGE_BASE_INDEXING, "blocked indexing"));
          return blocked.stream()
              .filter(indexing -> requeuedIds.contains(indexing.rawInformationItemId()))
              .map(DefaultRequeueExhaustedWorkUseCase::describe)
              .toList();
        });
  }

  private void recordRequeue(UUID rawInformationItemId, String stage, String previousReason) {
    activityRecordRepository.save(
        new ActivityRecord(
            null,
            clock.instant(),
            ACTIVITY_CATEGORY,
            "success",
            "requeued by an operator for " + stage + "; previous failure: " + previousReason,
            null,
            rawInformationItemId));
  }

  private static ExhaustedWork describe(RawInformationItem item) {
    return new ExhaustedWork(
        item.id(),
        item.processingState().failedStage(),
        true,
        item.processingState().failureReason());
  }

  private static ExhaustedWork describe(BlockedIndexing indexing) {
    return new ExhaustedWork(
        indexing.rawInformationItemId(),
        ExhaustedWork.KNOWLEDGE_BASE_INDEXING,
        indexing.recoverable(),
        indexing.reason());
  }
}
