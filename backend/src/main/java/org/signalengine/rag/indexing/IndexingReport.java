package org.signalengine.rag.indexing;

import java.util.List;
import java.util.Map;
import org.signalengine.rag.ComponentDescriptor;

/**
 * What an {@link IndexingPipeline} did with one {@link IndexableContent}: how many passages it
 * produced, embedded and persisted, and how many were skipped or failed.
 *
 * <p>Failures are explicit here, never swallowed. {@code embedded} is {@code 0} and an exception
 * propagates when the embedding call itself fails; a per-passage persistence failure is counted in
 * {@code failed} and described in {@code notes}, and the rest of the passages are still attempted.
 *
 * @param contentId the content that was indexed; never blank
 * @param passages the number of passages chunking produced; never negative
 * @param embedded the number of passages an embedding vector was produced for; never negative
 * @param persisted the number of passages stored (inserted or updated); never negative
 * @param skipped the number of passages deliberately not stored; never negative
 * @param failed the number of passages that could not be stored; never negative
 * @param pipeline the descriptor of the pipeline that ran; never {@code null}
 * @param notes human-readable notices (a persist failure, a degraded chunk&hellip;); never {@code
 *     null}
 * @param metadata open run-level detail (inserted/updated breakdown, chunker, embedding
 *     model&hellip;); keys never blank
 */
public record IndexingReport(
    String contentId,
    int passages,
    int embedded,
    int persisted,
    int skipped,
    int failed,
    ComponentDescriptor pipeline,
    List<String> notes,
    Map<String, String> metadata) {

  public IndexingReport {
    if (contentId == null || contentId.isBlank()) {
      throw new IllegalArgumentException("contentId must not be blank");
    }
    requireNonNegative(passages, "passages");
    requireNonNegative(embedded, "embedded");
    requireNonNegative(persisted, "persisted");
    requireNonNegative(skipped, "skipped");
    requireNonNegative(failed, "failed");
    if (pipeline == null) {
      throw new IllegalArgumentException("pipeline descriptor must not be null");
    }
    notes = notes == null ? List.of() : List.copyOf(notes);
    if (notes.stream().anyMatch(note -> note == null || note.isBlank())) {
      throw new IllegalArgumentException("notes must not contain blank entries");
    }
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    if (metadata.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
      throw new IllegalArgumentException("metadata keys must not be blank");
    }
  }

  /** Every passage was embedded and stored, nothing skipped or failed. */
  public boolean fullyIndexed() {
    return failed == 0 && skipped == 0 && persisted == passages && embedded == passages;
  }

  private static void requireNonNegative(int value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must not be negative: " + value);
    }
  }
}
