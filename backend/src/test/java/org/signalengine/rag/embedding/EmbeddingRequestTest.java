package org.signalengine.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The embedding request contract: role is explicit, blank inputs are rejected, empty is a no-op.
 */
class EmbeddingRequestTest {

  @Test
  void forQueryAndForPassagesSetTheRole() {
    assertThat(EmbeddingRequest.forQuery("what is X?").role()).isEqualTo(TextRole.QUERY);
    assertThat(EmbeddingRequest.forPassages(List.of("a", "b")).role()).isEqualTo(TextRole.PASSAGE);
  }

  @Test
  void preservesTextOrder() {
    EmbeddingRequest request = EmbeddingRequest.forPassages(List.of("first", "second", "third"));
    assertThat(request.texts()).containsExactly("first", "second", "third");
  }

  @Test
  void anEmptyListIsAllowedAndIsANoOp() {
    EmbeddingRequest request = new EmbeddingRequest(List.of(), TextRole.PASSAGE);
    assertThat(request.isEmpty()).isTrue();
  }

  @Test
  void rejectsABlankOrNullTextInsideANonEmptyList() {
    assertThatThrownBy(() -> EmbeddingRequest.forPassages(List.of("ok", "   ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("index 1");
    var withNull = new java.util.ArrayList<String>();
    withNull.add("ok");
    withNull.add(null);
    assertThatThrownBy(() -> new EmbeddingRequest(withNull, TextRole.QUERY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullRole() {
    assertThatThrownBy(() -> new EmbeddingRequest(List.of("x"), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
