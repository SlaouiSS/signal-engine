package org.signalengine.rag.context;

import java.util.Map;
import org.signalengine.rag.provenance.Provenance;

/**
 * One passage selected into a {@link Context}.
 *
 * <p>Distinct from {@link org.signalengine.rag.retrieval.RetrievedPassage}: a context passage has
 * been chosen (its retrieval score is no longer meaningful once fused/reranked/refined) and its
 * {@code text} may have been trimmed or compressed. It keeps {@code passageId} and {@code
 * provenance} so every passage in the context can still be traced back to its source and cited.
 *
 * @param passageId identifier carried through from retrieval; never blank
 * @param text the passage text as it should be presented to a generator; never blank
 * @param provenance provenance carried through from retrieval unchanged; never {@code null}
 * @param metadata open, immutable per-passage metadata carried or added during assembly; keys never
 *     blank
 */
public record ContextPassage(
    String passageId, String text, Provenance provenance, Map<String, String> metadata) {

  public ContextPassage {
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

  public static ContextPassage of(String passageId, String text, Provenance provenance) {
    return new ContextPassage(passageId, text, provenance, Map.of());
  }
}
