package org.signalengine.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;

/** The unit an {@link IndexedPassageStore} persists: passage + embedding + identities. */
class IndexedPassageTest {

  private static final ComponentDescriptor CHUNKER =
      new ComponentDescriptor(RagComponentType.CHUNKER, "structure-aware", "1");
  private static final EmbeddingModelDescriptor MODEL =
      new EmbeddingModelDescriptor("ollama", "embeddinggemma", "embed/v1", 4);
  private static final Passage PASSAGE = IndexingTestDoubles.passage("c-1", 0, "a passage of text");

  private static IndexedPassage of(float[] embedding) {
    return new IndexedPassage("c-1", PASSAGE, CHUNKER, embedding, MODEL);
  }

  @Test
  void exposesPassageIdContentIdAndDimension() {
    IndexedPassage indexed = of(new float[] {0.1f, 0.2f, 0.3f, 0.4f});

    assertThat(indexed.passageId()).isEqualTo(PASSAGE.passageId());
    assertThat(indexed.contentId()).isEqualTo("c-1");
    assertThat(indexed.dimension()).isEqualTo(4);
  }

  @Test
  void defensivelyCopiesTheEmbedding() {
    float[] source = {0.1f, 0.2f, 0.3f, 0.4f};
    IndexedPassage indexed = of(source);
    source[0] = 9f;
    assertThat(indexed.embedding()[0]).isEqualTo(0.1f);
  }

  @Test
  void rejectsAnEmbeddingWhoseLengthDisagreesWithTheModelDimension() {
    assertThatThrownBy(() -> of(new float[] {0.1f, 0.2f, 0.3f}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not truncated or padded");
  }

  @Test
  void rejectsNonFiniteComponents() {
    assertThatThrownBy(() -> of(new float[] {0.1f, Float.NaN, 0.3f, 0.4f}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullOrEmptyEmbeddingAndMissingIdentities() {
    assertThatThrownBy(() -> of(new float[0])).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> of(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new IndexedPassage(" ", PASSAGE, CHUNKER, new float[] {1, 2, 3, 4}, MODEL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new IndexedPassage("c-1", null, CHUNKER, new float[] {1, 2, 3, 4}, MODEL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new IndexedPassage("c-1", PASSAGE, null, new float[] {1, 2, 3, 4}, MODEL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new IndexedPassage("c-1", PASSAGE, CHUNKER, new float[] {1, 2, 3, 4}, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
