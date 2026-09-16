package org.signalengine.rag.indexing;

/**
 * An indexing operation could not proceed &mdash; embedding failed, an embedding was the wrong
 * dimension, provenance or identity was invalid, or the store rejected the whole batch.
 *
 * <p>Unchecked, and defined in the RAG core so the contract carries no infrastructure exception
 * type. Per-passage persistence failures are reported on the {@link IndexingReport} instead; this
 * is for failures that abort the content.
 */
public final class IndexingException extends RuntimeException {

  public IndexingException(String message) {
    super(message);
  }

  public IndexingException(String message, Throwable cause) {
    super(message, cause);
  }
}
