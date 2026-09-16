package org.signalengine.application.signal;

import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;
import org.signalengine.rag.indexing.IndexingReport;

/**
 * Output port: index one raw information item's content into the searchable knowledge base
 * (docs/07-rag.md Section 21.7; docs/05-data-model.md Section 16).
 *
 * <p>Called once an item is confirmed relevant and therefore becomes retained knowledge
 * (docs/02-functional-spec.md Section 12.3, workflow W9; docs/08-ingestion.md Section 22 —
 * ingestion decides what belongs in the knowledge base, RAG only makes it searchable). This port
 * performs no chunking, embedding, or persistence of its own; it delegates entirely to the existing
 * {@link org.signalengine.rag.indexing.IndexingPipeline}.
 *
 * <p>Indexing is a best-effort enrichment, not a gate in the {@link
 * org.signalengine.domain.ProcessingState} pipeline: a failure here does not affect the
 * relevance-assessment outcome (see {@link DefaultProcessRelevantInformationUseCase}).
 */
public interface KnowledgeBaseIndexer {

  /**
   * @param item the raw information item to index; must have normalized content
   * @param source the item's source, for provenance
   * @return what the indexing pipeline did with this content
   * @throws org.signalengine.rag.embedding.EmbeddingException if the embedding call failed
   * @throws org.signalengine.rag.indexing.IndexingException if the embedding result violated its
   *     contract
   * @throws IllegalArgumentException if the item has no normalized content to index
   */
  IndexingReport index(RawInformationItem item, Source source);
}
