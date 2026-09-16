package org.signalengine.rag.generation;

/**
 * A {@link Generator} could not obtain a valid grounded answer &mdash; the provider was
 * unavailable, the call timed out, or the model output failed structural validation even after the
 * one permitted repair attempt.
 *
 * <p>This is <b>not</b> the "context does not support an answer" outcome: that is a normal {@link
 * RagAnswer} with {@code answered = false} and an explanation. A generation exception means no
 * trustworthy answer could be produced at all, and the caller (a query use case, an API) should
 * surface "answering is temporarily unavailable" rather than anything model-derived
 * (docs/06-ai-agents.md Section 4.7, item 9).
 *
 * <p>Unchecked, consistent with {@link org.signalengine.rag.embedding.EmbeddingException} and
 * {@link org.signalengine.rag.indexing.IndexingException}: a stage failure the pipeline propagates.
 */
public class GenerationException extends RuntimeException {

  public GenerationException(String message) {
    super(message);
  }

  public GenerationException(String message, Throwable cause) {
    super(message, cause);
  }
}
