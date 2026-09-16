package org.signalengine.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The generic "which model produced this embedding" descriptor. */
class EmbeddingModelDescriptorTest {

  @Test
  void carriesProviderModelVersionAndDimension() {
    EmbeddingModelDescriptor descriptor =
        new EmbeddingModelDescriptor("ollama", "embeddinggemma", "embed/v1", 768);

    assertThat(descriptor.provider()).isEqualTo("ollama");
    assertThat(descriptor.model()).isEqualTo("embeddinggemma");
    assertThat(descriptor.version()).isEqualTo("embed/v1");
    assertThat(descriptor.dimension()).isEqualTo(768);
  }

  @Test
  void rejectsBlankStrings() {
    assertThatThrownBy(() -> new EmbeddingModelDescriptor(" ", "m", "v", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingModelDescriptor("p", "", "v", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingModelDescriptor("p", "m", null, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsANegativeDimension() {
    assertThatThrownBy(() -> new EmbeddingModelDescriptor("p", "m", "v", -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unspecifiedHasDimensionZero() {
    assertThat(EmbeddingModelDescriptor.unspecified().dimension()).isZero();
  }
}
