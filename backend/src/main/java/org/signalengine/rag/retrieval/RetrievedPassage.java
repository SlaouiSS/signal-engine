package org.signalengine.rag.retrieval;

import java.util.Map;
import org.signalengine.rag.provenance.Provenance;

/**
 * One candidate passage returned by a {@link Retriever}.
 *
 * @param passageId stable identifier of this passage within its source store; never blank
 * @param text the passage content; never blank
 * @param provenance where the passage came from; never {@code null}
 * @param score the retriever's relevance score for this passage against the query; higher means
 *     more relevant. The scale is strategy-defined (cosine similarity, BM25, fused hybrid
 *     score&hellip;) and comparable only within one {@link RetrievalResult}.
 * @param metadata open, immutable per-passage metadata (embedding model, chunk index, language,
 *     original offsets&hellip;) that later stages or evaluation may use; keys never blank
 */
public record RetrievedPassage(
    String passageId,
    String text,
    Provenance provenance,
    double score,
    Map<String, String> metadata) {

  public RetrievedPassage {
    if (passageId == null || passageId.isBlank()) {
      throw new IllegalArgumentException("passageId must not be blank");
    }
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
    if (provenance == null) {
      throw new IllegalArgumentException("provenance must not be null");
    }
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    if (metadata.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
      throw new IllegalArgumentException("metadata keys must not be blank");
    }
  }

  /** A passage with a score and no extra metadata. */
  public static RetrievedPassage of(
      String passageId, String text, Provenance provenance, double score) {
    return new RetrievedPassage(passageId, text, provenance, score, Map.of());
  }
}
