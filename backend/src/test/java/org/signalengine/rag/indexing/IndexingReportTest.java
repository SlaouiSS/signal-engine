package org.signalengine.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;

/** The indexing report: explicit counts, explicit failure notes. */
class IndexingReportTest {

  private static final ComponentDescriptor PIPELINE =
      new ComponentDescriptor(
          RagComponentType.INDEXING_PIPELINE, "staged-indexing-pipeline", "staged/v1");

  private static IndexingReport of(
      int passages, int embedded, int persisted, int skipped, int failed) {
    return new IndexingReport(
        "c-1", passages, embedded, persisted, skipped, failed, PIPELINE, List.of(), Map.of());
  }

  @Test
  void fullyIndexedWhenEverythingWasEmbeddedAndPersisted() {
    assertThat(of(3, 3, 3, 0, 0).fullyIndexed()).isTrue();
    assertThat(of(3, 3, 2, 0, 1).fullyIndexed()).isFalse();
    assertThat(of(3, 0, 0, 3, 0).fullyIndexed()).isFalse();
  }

  @Test
  void rejectsNegativeCountsBlankContentAndNullPipeline() {
    assertThatThrownBy(() -> of(-1, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IndexingReport(" ", 0, 0, 0, 0, 0, PIPELINE, List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IndexingReport("c-1", 0, 0, 0, 0, 0, null, List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBlankNotes() {
    assertThatThrownBy(
            () -> new IndexingReport("c-1", 1, 1, 1, 0, 0, PIPELINE, List.of("   "), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void carriesNotesAndMetadata() {
    IndexingReport report =
        new IndexingReport(
            "c-1",
            2,
            2,
            1,
            0,
            1,
            PIPELINE,
            List.of("passage p-2 not stored: boom"),
            Map.of("inserted", "1", "updated", "0"));
    assertThat(report.notes()).containsExactly("passage p-2 not stored: boom");
    assertThat(report.metadata()).containsEntry("inserted", "1");
  }
}
