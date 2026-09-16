package org.signalengine.rag.indexing;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;

/**
 * The offline path that turns content into passages a {@link
 * org.signalengine.rag.retrieval.Retriever} can later find:
 *
 * <pre>
 *   Content → Chunking → Metadata enrichment → Embedding → Persistence
 * </pre>
 *
 * <p>{@link StagedIndexingPipeline} is the supplied implementation, composed from a {@code
 * Chunker}, an {@code EmbeddingModel} and an {@link IndexedPassageStore}. Parsing, chunking,
 * embedding and persistence are all behind contracts; none of their technologies appear in the RAG
 * core.
 *
 * <p>Indexing stays a separate concern from the query-time pipeline: it runs offline, on a
 * different schedule, and the runtime {@code RagPipeline} never depends on this package.
 */
@FunctionalInterface
public interface IndexingPipeline {

  IndexingReport index(IndexableContent content);

  /** Identity and configuration of the concrete pipeline; overridden by real pipelines. */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.INDEXING_PIPELINE);
  }
}
