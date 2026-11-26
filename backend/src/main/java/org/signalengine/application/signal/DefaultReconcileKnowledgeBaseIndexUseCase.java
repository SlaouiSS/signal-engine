package org.signalengine.application.signal;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.ai.AiCapabilityFailureException;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.domain.ActivityRecord;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;
import org.signalengine.rag.indexing.IndexingReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link ReconcileKnowledgeBaseIndexUseCase}
 * (docs/adr/0018-pipeline-reliability.md).
 *
 * <p>It owns the sweep's policy: which items to take from the {@link KnowledgeBaseIndexer}'s
 * backlog, what each outcome means, and how a suspended capability is reported. It owns nothing
 * about <em>how</em> indexing is made safe: the {@link KnowledgeBaseIndexer} port claims the item,
 * prepares passages and embeddings outside any transaction, and replaces them atomically, so
 * running this concurrently with the indexing that follows relevance assessment, or with itself, is
 * harmless.
 *
 * <p>An item that needs a suspended capability costs one failed lookup, not a call, so the sweep
 * carries on and one activity record summarises the suspension.
 */
public final class DefaultReconcileKnowledgeBaseIndexUseCase
    implements ReconcileKnowledgeBaseIndexUseCase {

  private static final Logger log =
      LoggerFactory.getLogger(DefaultReconcileKnowledgeBaseIndexUseCase.class);
  private static final String ACTIVITY_CATEGORY = "knowledge_base_indexing";

  private final KnowledgeBaseIndexer knowledgeBaseIndexer;
  private final RawInformationItemRepository rawInformationItemRepository;
  private final SourceRepository sourceRepository;
  private final ActivityRecordRepository activityRecordRepository;
  private final Clock clock;
  private final int maxFailedAttempts;
  private final int batchLimit;

  public DefaultReconcileKnowledgeBaseIndexUseCase(
      KnowledgeBaseIndexer knowledgeBaseIndexer,
      RawInformationItemRepository rawInformationItemRepository,
      SourceRepository sourceRepository,
      ActivityRecordRepository activityRecordRepository,
      Clock clock,
      int maxFailedAttempts,
      int batchLimit) {
    this.knowledgeBaseIndexer = knowledgeBaseIndexer;
    this.rawInformationItemRepository = rawInformationItemRepository;
    this.sourceRepository = sourceRepository;
    this.activityRecordRepository = activityRecordRepository;
    this.clock = clock;
    this.maxFailedAttempts = maxFailedAttempts;
    this.batchLimit = batchLimit;
  }

  @Override
  public List<IndexReconciliationReport> reconcilePending() {
    List<UUID> awaitingIndexing =
        knowledgeBaseIndexer.findItemIdsAwaitingIndexing(
            clock.instant(), maxFailedAttempts, batchLimit);

    List<IndexReconciliationReport> reports = new ArrayList<>();
    for (UUID itemId : awaitingIndexing) {
      rawInformationItemRepository
          .findById(itemId)
          .ifPresent(item -> reports.add(reconcileOne(item)));
    }
    recordSuspensionSummary(reports);
    return List.copyOf(reports);
  }

  private void recordSuspensionSummary(List<IndexReconciliationReport> reports) {
    List<IndexReconciliationReport> suspended =
        reports.stream()
            .filter(report -> report.outcome() == IndexReconciliationReport.Outcome.SUSPENDED)
            .toList();
    if (!suspended.isEmpty()) {
      recordActivity(
          null,
          null,
          "failure",
          suspended.size()
              + " item(s) wait for a suspended capability to be indexed: "
              + suspended.get(0).detail());
    }
  }

  private IndexReconciliationReport reconcileOne(RawInformationItem item) {
    Optional<Source> source = sourceRepository.findById(item.sourceId());
    if (source.isEmpty()) {
      recordActivity(
          null,
          item,
          "failure",
          "indexing skipped: source " + item.sourceId() + " no longer exists");
      return IndexReconciliationReport.skipped(item.id(), "source no longer exists");
    }
    try {
      IndexingReport indexingReport = knowledgeBaseIndexer.index(item, source.get());
      return toReport(item, source.get(), indexingReport);
    } catch (RuntimeException failure) {
      return toFailureReport(item, source.get(), failure);
    }
  }

  private IndexReconciliationReport toReport(
      RawInformationItem item, Source source, IndexingReport indexingReport) {
    if (indexingReport.skipped() > 0) {
      return IndexReconciliationReport.skipped(
          item.id(), String.join("; ", indexingReport.notes()));
    }
    if (indexingReport.fullyIndexed()) {
      recordActivity(
          source,
          item,
          "success",
          "reconciled knowledge base index: " + indexingReport.persisted() + " passage(s)");
      return IndexReconciliationReport.indexed(item.id(), indexingReport.persisted());
    }
    String reason =
        "indexing incomplete: "
            + indexingReport.persisted()
            + "/"
            + indexingReport.passages()
            + " passage(s) stored"
            + (indexingReport.notes().isEmpty()
                ? ""
                : " (" + String.join("; ", indexingReport.notes()) + ")");
    recordActivity(source, item, "failure", reason);
    return IndexReconciliationReport.failed(item.id(), reason);
  }

  private IndexReconciliationReport toFailureReport(
      RawInformationItem item, Source source, RuntimeException failure) {
    boolean capabilitySuspended =
        AiCapabilityFailureException.findIn(failure)
            .map(AiError::capabilitySuspended)
            .orElse(false);
    if (capabilitySuspended) {
      return IndexReconciliationReport.suspended(item.id(), failure.getMessage());
    }
    log.warn("Reconciling the knowledge base index failed for item {}", item.id(), failure);
    String reason = "indexing failed: " + failure.getMessage();
    recordActivity(source, item, "failure", reason);
    return IndexReconciliationReport.failed(item.id(), reason);
  }

  private void recordActivity(
      Source source, RawInformationItem item, String outcome, String message) {
    activityRecordRepository.save(
        new ActivityRecord(
            null,
            clock.instant(),
            ACTIVITY_CATEGORY,
            outcome,
            message,
            source == null ? null : source.id(),
            item == null ? null : item.id()));
  }
}
