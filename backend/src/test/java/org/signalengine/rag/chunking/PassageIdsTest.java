package org.signalengine.rag.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The deterministic passage-identity strategy: reproducible, and sensitive to every input. */
class PassageIdsTest {

  @Test
  void sameInputsProduceTheSameId() {
    String a = PassageIds.forPassage("content-1", "structure-aware-chunker", "1", 0, "hello world");
    String b = PassageIds.forPassage("content-1", "structure-aware-chunker", "1", 0, "hello world");

    assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  void changingTheTextChangesTheId() {
    String original = PassageIds.forPassage("c", "impl", "1", 0, "the original passage text");
    String edited = PassageIds.forPassage("c", "impl", "1", 0, "the edited passage text");

    assertThat(edited).isNotEqualTo(original);
  }

  @Test
  void changingConfigurationVersionChangesTheId() {
    String v1 = PassageIds.forPassage("c", "impl", "1;maxCharsPerPassage=1200", 0, "text");
    String v2 = PassageIds.forPassage("c", "impl", "1;maxCharsPerPassage=600", 0, "text");

    assertThat(v2).isNotEqualTo(v1);
  }

  @Test
  void changingOrdinalContentIdOrImplementationChangesTheId() {
    String base = PassageIds.forPassage("c", "impl", "1", 0, "text");

    assertThat(PassageIds.forPassage("c", "impl", "1", 1, "text")).isNotEqualTo(base);
    assertThat(PassageIds.forPassage("other", "impl", "1", 0, "text")).isNotEqualTo(base);
    assertThat(PassageIds.forPassage("c", "other-impl", "1", 0, "text")).isNotEqualTo(base);
  }

  @Test
  void fieldBoundariesCannotBeConfused() {
    // Length-prefixing means "ab" + "c" and "a" + "bc" never collide.
    assertThat(PassageIds.forPassage("ab", "c", "1", 0, "x"))
        .isNotEqualTo(PassageIds.forPassage("a", "bc", "1", 0, "x"));
  }
}
