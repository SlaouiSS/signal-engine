package org.signalengine.rag.indexing;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;

/**
 * One chunked {@link Passage} together with its embedding and the identities needed to store it
 * idempotently: which chunker produced the passage, and which embedding model produced the vector.
 *
 * <p>The logical identity of an indexed passage is {@code passage.passageId()} (which already
 * encodes content, chunker id, chunker version, ordinal and text &mdash; {@link
 * org.signalengine.rag.chunking.PassageIds}) plus the embedding model identity. A store keys on
 * that; it never invents a random id.
 *
 * @param contentId identifier of the content this passage was chunked from; never blank
 * @param passage the passage; never {@code null}
 * @param chunker the descriptor of the chunker that produced {@code passage}; never {@code null}
 * @param embedding the passage's embedding vector; never {@code null}, non-empty, all values
 *     finite, length equal to {@code embeddingModel.dimension()} &mdash; never truncated or padded
 * @param embeddingModel which model produced {@code embedding}; never {@code null}
 */
public record IndexedPassage(
    String contentId,
    Passage passage,
    ComponentDescriptor chunker,
    float[] embedding,
    EmbeddingModelDescriptor embeddingModel) {

  public IndexedPassage {
    if (contentId == null || contentId.isBlank()) {
      throw new IllegalArgumentException("contentId must not be blank");
    }
    if (passage == null) {
      throw new IllegalArgumentException("passage must not be null");
    }
    if (chunker == null) {
      throw new IllegalArgumentException("chunker descriptor must not be null");
    }
    if (embeddingModel == null) {
      throw new IllegalArgumentException("embeddingModel descriptor must not be null");
    }
    if (embedding == null || embedding.length == 0) {
      throw new IllegalArgumentException("embedding must not be null or empty");
    }
    if (embeddingModel.dimension() > 0 && embedding.length != embeddingModel.dimension()) {
      throw new IllegalArgumentException(
          "embedding has dimension "
              + embedding.length
              + " but the model declares "
              + embeddingModel.dimension()
              + " (not truncated or padded)");
    }
    for (int i = 0; i < embedding.length; i++) {
      if (!Float.isFinite(embedding[i])) {
        throw new IllegalArgumentException("embedding component " + i + " is not finite");
      }
    }
    embedding = embedding.clone();
  }

  /** The passage's deterministic id (see {@link org.signalengine.rag.chunking.PassageIds}). */
  public String passageId() {
    return passage.passageId();
  }

  public int dimension() {
    return embedding.length;
  }

  /** A defensive copy of the embedding vector. */
  @Override
  public float[] embedding() {
    return embedding.clone();
  }
}
