package org.signalengine.rag.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The query contract, including the first-class {@code topK}. */
class QueryTest {

  @Test
  void ofDefaultsTopKAndOfWithTopKSetsIt() {
    assertThat(Query.of("what is X?").topK()).isEqualTo(Query.DEFAULT_TOP_K);
    assertThat(Query.of("what is X?", 12).topK()).isEqualTo(12);
  }

  @Test
  void rejectsTopKOutOfRange() {
    assertThatThrownBy(() -> Query.of("q", 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Query.of("q", -1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Query.of("q", Query.MAX_TOP_K + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Query.of("q", Query.MAX_TOP_K).topK()).isEqualTo(Query.MAX_TOP_K);
  }

  @Test
  void withHelpersCarryTopKThrough() {
    Query base = Query.of("original", 7).withMetadataFilter("sourceId", "s1");

    assertThat(base.withText("rewritten").topK()).isEqualTo(7);
    assertThat(base.withText("rewritten").metadataFilters()).containsEntry("sourceId", "s1");
    assertThat(base.withMetadataFilter("contentId", "c1").topK()).isEqualTo(7);
    assertThat(base.withTopK(3).topK()).isEqualTo(3);
    assertThat(base.withTopK(3).metadataFilters()).containsEntry("sourceId", "s1");
  }

  @Test
  void stillRejectsBlankTextAndBlankFilterKeys() {
    assertThatThrownBy(() -> Query.of("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Query("q", java.util.Map.of(" ", "v"), java.util.Map.of(), 5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
