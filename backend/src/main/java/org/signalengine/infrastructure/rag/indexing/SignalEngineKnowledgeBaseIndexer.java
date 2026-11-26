package org.signalengine.infrastructure.rag.indexing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;
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

  SignalEngineKnowledgeBaseIndexer(
      IndexingPipeline indexingPipeline, KnowledgeBaseIndexBacklog backlog) {
    this.indexingPipeline = indexingPipeline;
    this.backlog = backlog;
  }

  @Override
  public IndexingReport index(RawInformationItem item, Source source) {
    return indexingPipeline.index(SignalEngineIndexableContent.from(item, source));
  }

  @Override
  public List<UUID> findItemIdsAwaitingIndexing(Instant now, int maxFailedAttempts, int limit) {
    return backlog.findItemIdsAwaitingIndexing(now, maxFailedAttempts, limit);
  }
}
