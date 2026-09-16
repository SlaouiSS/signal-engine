package org.signalengine.rag.retrieval;

import java.util.List;
import java.util.Map;

/**
 * The ordered outcome of one retrieval step: the candidate passages, most relevant first, plus
 * generic metadata about how retrieval ran.
 *
 * <p>This is an independently usable output &mdash; a caller can run {@code Query -> Retriever} and
 * inspect the result without assembling a context or generating an answer.
 *
 * @param passages candidate passages in relevance order (index 0 is the best match); never {@code
 *     null}, may be empty when nothing matched
 * @param metadata open, immutable metadata about the retrieval run (strategy used, candidate pool
 *     size, embedding model, filters applied&hellip;); keys never blank
 */
public record RetrievalResult(List<RetrievedPassage> passages, Map<String, String> metadata) {

  public RetrievalResult {
    passages = passages == null ? List.of() : List.copyOf(passages);
    if (passages.stream().anyMatch(passage -> passage == null)) {
      throw new IllegalArgumentException("passages must not contain null");
    }
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    if (metadata.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
      throw new IllegalArgumentException("metadata keys must not be blank");
    }
  }

  public static RetrievalResult of(List<RetrievedPassage> passages) {
    return new RetrievalResult(passages, Map.of());
  }

  public boolean isEmpty() {
    return passages.isEmpty();
  }
}
