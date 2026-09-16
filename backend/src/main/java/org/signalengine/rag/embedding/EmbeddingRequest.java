package org.signalengine.rag.embedding;

import java.util.List;

/**
 * One embedding call: the texts to embed and the role they play.
 *
 * <p>An empty {@code texts} list is allowed and yields an empty {@link EmbeddingResult} &mdash; a
 * no-op. Blank or {@code null} texts inside a non-empty list are rejected: an embedding model must
 * not be asked to represent nothing, and the core never silently drops or substitutes an input.
 *
 * @param texts the texts to embed, in the order the vectors must be returned; never {@code null}
 * @param role whether these texts are queries or passages; never {@code null}
 */
public record EmbeddingRequest(List<String> texts, TextRole role) {

  public EmbeddingRequest {
    if (texts == null) {
      throw new IllegalArgumentException("texts must not be null");
    }
    if (role == null) {
      throw new IllegalArgumentException("role must not be null");
    }
    for (int i = 0; i < texts.size(); i++) {
      if (texts.get(i) == null || texts.get(i).isBlank()) {
        throw new IllegalArgumentException("text at index " + i + " is null or blank");
      }
    }
    texts = List.copyOf(texts);
  }

  /** A request to embed one query. */
  public static EmbeddingRequest forQuery(String query) {
    return new EmbeddingRequest(List.of(query), TextRole.QUERY);
  }

  /** A request to embed one or more passages. */
  public static EmbeddingRequest forPassages(List<String> passages) {
    return new EmbeddingRequest(passages, TextRole.PASSAGE);
  }

  public boolean isEmpty() {
    return texts.isEmpty();
  }
}
