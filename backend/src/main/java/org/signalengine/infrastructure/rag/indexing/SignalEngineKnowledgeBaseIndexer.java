package org.signalengine.infrastructure.rag.indexing;

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
 * &mdash; no new chunking, embedding, or persistence logic (docs/07-rag.md Section 21.7, 23).
 */
class SignalEngineKnowledgeBaseIndexer implements KnowledgeBaseIndexer {

  private final IndexingPipeline indexingPipeline;

  SignalEngineKnowledgeBaseIndexer(IndexingPipeline indexingPipeline) {
    this.indexingPipeline = indexingPipeline;
  }

  @Override
  public IndexingReport index(RawInformationItem item, Source source) {
    return indexingPipeline.index(SignalEngineIndexableContent.from(item, source));
  }
}
