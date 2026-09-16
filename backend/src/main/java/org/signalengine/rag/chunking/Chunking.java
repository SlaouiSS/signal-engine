package org.signalengine.rag.chunking;

import java.util.List;
import java.util.Map;
import org.signalengine.rag.ComponentDescriptor;

/**
 * The result of chunking one content unit: the ordered passages, and an honest account of which
 * chunker and configuration produced them.
 *
 * <p>This is the hand-off point to a future indexing/persistence step. Nothing here is persisted
 * yet &mdash; Task 8.2 stops at this representation.
 *
 * <p>{@code chunker} makes the strategy explicit: a {@link Chunking} produced by the deterministic
 * {@link StructureAwareChunker} and one produced by a semantic chunker are never confused, and a
 * later evaluator can group results by {@code chunker} to compare configurations on one corpus.
 * {@code metadata} carries run-level detail (passage count, source length, strategy, degraded
 * windows for a semantic run&hellip;).
 *
 * @param contentId identifier of the {@link org.signalengine.rag.indexing.IndexableContent} that
 *     was chunked; never blank
 * @param passages the passages in document order; never {@code null}, may be empty when the content
 *     yielded nothing chunkable
 * @param chunker type ({@link org.signalengine.rag.RagComponentType#CHUNKER}), implementation id
 *     and configuration version of the chunker that ran; never {@code null}
 * @param warnings human-readable notices about the run (degraded windows, dropped blocks&hellip;);
 *     never {@code null}, may be empty
 * @param metadata open, immutable run-level metadata; keys never blank
 */
public record Chunking(
    String contentId,
    List<Passage> passages,
    ComponentDescriptor chunker,
    List<String> warnings,
    Map<String, String> metadata) {

  public Chunking {
    if (contentId == null || contentId.isBlank()) {
      throw new IllegalArgumentException("contentId must not be blank");
    }
    passages = passages == null ? List.of() : List.copyOf(passages);
    if (passages.stream().anyMatch(passage -> passage == null)) {
      throw new IllegalArgumentException("passages must not contain null");
    }
    if (chunker == null) {
      throw new IllegalArgumentException("chunker descriptor must not be null");
    }
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
    if (warnings.stream().anyMatch(warning -> warning == null || warning.isBlank())) {
      throw new IllegalArgumentException("warnings must not contain blank entries");
    }
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    if (metadata.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
      throw new IllegalArgumentException("metadata keys must not be blank");
    }
  }

  public int passageCount() {
    return passages.size();
  }
}
