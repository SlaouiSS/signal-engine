package org.signalengine.infrastructure.rag.indexing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.signalengine.application.signal.BlockedIndexing;
import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;
import org.signalengine.infrastructure.ai.AiCallDeadline;
import org.signalengine.infrastructure.rag.chunking.SignalEngineIndexableContent;
import org.signalengine.rag.indexing.IndexingPipeline;
import org.signalengine.rag.indexing.IndexingReport;

/**
 * Adapter for {@link KnowledgeBaseIndexer}: maps the item to an {@link
 * org.signalengine.rag.indexing.IndexableContent} via the existing {@link
 * SignalEngineIndexableContent} bridge and runs it through the existing {@link IndexingPipeline}
 * &mdash; no new chunking, embedding, or persistence logic (docs/07-rag.md Section 21.7, 23). The
 * items that wait for indexing are found by {@link KnowledgeBaseIndexBacklog}.
 */
class SignalEngineKnowledgeBaseIndexer implements KnowledgeBaseIndexer {

  private final IndexingPipeline indexingPipeline;
  private final KnowledgeBaseIndexBacklog backlog;

  private final AiCallDeadline callDeadline;
  private final Duration timeBudget;

  SignalEngineKnowledgeBaseIndexer(
      IndexingPipeline indexingPipeline,
      KnowledgeBaseIndexBacklog backlog,
      AiCallDeadline callDeadline,
      Duration timeBudget) {
    this.indexingPipeline = indexingPipeline;
    this.backlog = backlog;
    this.callDeadline = callDeadline;
    this.timeBudget = timeBudget;
  }

  @Override
  public IndexingReport index(RawInformationItem item, Source source) {
    return callDeadline.within(
        timeBudget, () -> indexingPipeline.index(SignalEngineIndexableContent.from(item, source)));
  }

  @Override
  public List<UUID> findItemIdsAwaitingIndexing(Instant now, int limit) {
    return backlog.findItemIdsAwaitingIndexing(now, limit);
  }

  @Override
  public List<BlockedIndexing> findBlockedIndexing() {
    return backlog.findBlockedIndexing();
  }

  @Override
  public List<UUID> requeueBlockedIndexing() {
    return backlog.requeueBlockedIndexing();
  }
}
