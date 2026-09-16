package org.signalengine.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The embedding result enforces every guarantee: count, dimension, finiteness, no pad/truncate. */
class EmbeddingResultTest {

  private static final EmbeddingModelDescriptor MODEL = EmbeddingModelDescriptor.unspecified();

  private static EmbeddingResult of(List<float[]> vectors, int dimension) {
    return new EmbeddingResult(vectors, dimension, MODEL);
  }

  @Test
  void acceptsWellFormedVectorsAndDefensivelyCopies() {
    float[] first = {0.1f, 0.2f, 0.3f};
    EmbeddingResult result = of(List.of(first, new float[] {0.4f, 0.5f, 0.6f}), 3);

    assertThat(result.count()).isEqualTo(2);
    assertThat(result.dimension()).isEqualTo(3);
    first[0] = 99f; // mutating the source must not affect the stored vector
    assertThat(result.vector(0)[0]).isEqualTo(0.1f);
  }

  @Test
  void emptyResultHasDimensionZero() {
    EmbeddingResult empty = EmbeddingResult.empty(MODEL);
    assertThat(empty.count()).isZero();
    assertThat(empty.dimension()).isZero();
    assertThatThrownBy(() -> of(List.of(), 5)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsADimensionMismatchWithoutPaddingOrTruncating() {
    assertThatThrownBy(() -> of(List.of(new float[] {1f, 2f, 3f}, new float[] {1f, 2f}), 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not truncated or padded");
  }

  @Test
  void rejectsNonFiniteComponents() {
    assertThatThrownBy(() -> of(List.of(new float[] {1f, Float.NaN, 3f}), 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not finite");
    assertThatThrownBy(() -> of(List.of(new float[] {1f, Float.POSITIVE_INFINITY, 3f}), 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAnEmptyVector() {
    assertThatThrownBy(() -> of(List.of(new float[0]), 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAPositiveDimensionThatDoesNotMatch() {
    assertThatThrownBy(() -> of(List.of(new float[] {1f, 2f}), 4))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsADimensionThatDisagreesWithTheModelDescriptor() {
    EmbeddingModelDescriptor model =
        new EmbeddingModelDescriptor("ollama", "embeddinggemma", "1", 768);
    assertThatThrownBy(() -> new EmbeddingResult(List.of(new float[] {1f, 2f, 3f}), 3, model))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("768");
  }
}
