package org.signalengine.rag.indexing;

import java.util.Map;
import org.signalengine.rag.provenance.Provenance;

/**
 * A unit of content offered to an {@link IndexingPipeline} for indexing. Generic on purpose: it is
 * text plus the {@link Provenance} that must survive into every passage the pipeline derives from
 * it, plus open metadata. How the text is parsed, chunked, enriched and embedded is entirely the
 * pipeline implementation's concern (a later task).
 *
 * @param contentId caller's identifier for this content; never blank
 * @param text the raw text to index; never blank
 * @param provenance provenance to attach to every passage derived from this content; never {@code
 *     null}
 * @param metadata open, immutable metadata (language, published date, content type&hellip;); keys
 *     never blank
 */
public record IndexableContent(
    String contentId, String text, Provenance provenance, Map<String, String> metadata) {

  public IndexableContent {
    if (contentId == null || contentId.isBlank()) {
      throw new IllegalArgumentException("contentId must not be blank");
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
