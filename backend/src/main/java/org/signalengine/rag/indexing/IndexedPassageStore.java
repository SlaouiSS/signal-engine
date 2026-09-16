package org.signalengine.rag.indexing;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;

/**
 * The write side of the RAG index: persist a passage and its embedding so a future {@link
 * org.signalengine.rag.retrieval.Retriever} can find it.
 *
 * <p>Generic by design &mdash; the contract says nothing about PostgreSQL, pgvector, or any store.
 * It is the symmetric counterpart of {@code Retriever}: implementations sit in the infrastructure
 * layer, and swapping one for another changes nothing that depends on this interface.
 *
 * <p>Implementations must be <b>idempotent</b>: {@link #save} with a passage whose {@link
 * IndexedPassage#passageId()} and {@link IndexedPassage#embeddingModel()} match an existing entry
 * refreshes that entry in place ({@link PassagePersistOutcome#UPDATED}) &mdash; never a duplicate.
 * A different chunker configuration yields a different {@code passageId} and coexists; a different
 * embedding model yields a separate entry for the same passage and coexists. Nothing is silently
 * deleted, truncated, or padded; a failure is thrown, not swallowed.
 */
@FunctionalInterface
public interface IndexedPassageStore {

  PassagePersistOutcome save(IndexedPassage passage);

  /** Identity and configuration of the concrete store; overridden by real implementations. */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.INDEXED_PASSAGE_STORE);
  }
}
