package org.signalengine.rag.chunking;

import java.util.Map;
import org.signalengine.rag.provenance.Provenance;

/**
 * One passage produced by chunking: a contiguous, self-contained slice of a content unit, ready to
 * be embedded and retrieved later.
 *
 * <p>A passage is a <b>retrieval/indexing unit</b> and nothing more &mdash; not a business entity,
 * not a whole document, not an embedding, not a retrieval result. It is useful before any embedding
 * exists: it already carries its text, its order, and enough {@link Provenance} to trace it back to
 * the original content and cite it.
 *
 * @param passageId deterministic, stable identifier &mdash; the same content, chunker and
 *     configuration always yield the same id for the same slice ({@link PassageIds}); never blank
 * @param ordinal zero-based position of this passage within its content unit; never negative
 * @param text the passage text; never blank
 * @param provenance where the passage came from, carried through from the {@link
 *     org.signalengine.rag.indexing.IndexableContent}; never {@code null}. Its {@link
 *     Provenance#passageId()} equals this passage's {@code passageId}.
 * @param metadata open, immutable per-passage metadata &mdash; content type, language, publication
 *     time, chunk strategy, character span, and any further keys (see {@link IndexingMetadata}).
 *     Keys never blank.
 */
public record Passage(
    String passageId,
    int ordinal,
    String text,
    Provenance provenance,
    Map<String, String> metadata) {

  public Passage {
    if (passageId == null || passageId.isBlank()) {
      throw new IllegalArgumentException("passageId must not be blank");
    }
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative: " + ordinal);
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
}
