package org.signalengine.rag.embedding;

/**
 * An {@link EmbeddingModel} could not produce a valid result &mdash; the provider was unreachable
 * or timed out, or the response was malformed, non-finite, the wrong dimension, or the wrong count.
 *
 * <p>Unchecked, and defined in the RAG core so the contract carries no infrastructure exception
 * type. A concrete model maps a transport/provider failure onto this.
 */
public final class EmbeddingException extends RuntimeException {

  public EmbeddingException(String message) {
    super(message);
  }

  public EmbeddingException(String message, Throwable cause) {
    super(message, cause);
  }
}
