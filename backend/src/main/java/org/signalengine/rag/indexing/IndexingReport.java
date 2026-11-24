package org.signalengine.rag.indexing;

import java.util.List;
import java.util.Map;
import org.signalengine.rag.ComponentDescriptor;

/**
 * What an {@link IndexingPipeline} did with one {@link IndexableContent}: how many passages it
 * produced, embedded and persisted, and how many were skipped or failed.
 *
 * <p>Failures are explicit here, never swallowed. An exception propagates when chunking, embedding
 * or the atomic replacement fails: nothing is stored then, so a report never describes a partial
 * result. {@code skipped} is {@code 1} when nothing was done because another run holds the content,
 * or it is already completely indexed for this chunker configuration, or the run's claim was
 * superseded (docs/adr/0018-pipeline-reliability.md).
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

  /**
   * Every passage was embedded and stored, nothing skipped or failed — and there was at least one:
   * a content with no passage is not indexed, it has no completion marker.
   */
  public boolean fullyIndexed() {
    return passages > 0
        && failed == 0
        && skipped == 0
        && persisted == passages
        && embedded == passages;
  }

  private static void requireNonNegative(int value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must not be negative: " + value);
    }
  }
}
