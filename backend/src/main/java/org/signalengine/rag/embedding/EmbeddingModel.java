package org.signalengine.rag.embedding;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;

/**
 * Turns text into vectors: {@code text(s) → embedding vector(s)}.
 *
 * <p>The contract says nothing about the runtime. A local Ollama model, an NVIDIA Build endpoint, a
 * different local runtime, or an in-process model all implement this one method, and swapping one
 * for another changes nothing that depends on this interface.
 *
 * <p>An implementation must:
 *
 * <ul>
 *   <li>return exactly one vector per input text, in input order;
 *   <li>use the model's documented query vs. passage handling based on {@link
 *       EmbeddingRequest#role()};
 *   <li>return a {@link EmbeddingResult} whose invariants hold (non-empty, finite, one consistent
 *       dimension &mdash; the record enforces this);
 *   <li>throw {@link EmbeddingException} on a provider failure, a timeout, or an invalid response
 *       &mdash; never return a fabricated, truncated, or padded vector.
 * </ul>
 */
@FunctionalInterface
public interface EmbeddingModel {

  EmbeddingResult embed(EmbeddingRequest request);

  /**
   * Identity ({@link RagComponentType#EMBEDDING_MODEL}) and configuration version of this model.
   */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.EMBEDDING_MODEL);
  }
}
