package org.signalengine.rag.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link GenerationException} is an unchecked stage failure carrying a message and optional cause.
 */
class GenerationExceptionTest {

  @Test
  void carriesItsMessage() {
    GenerationException exception = new GenerationException("provider unavailable");
    assertThat(exception).isInstanceOf(RuntimeException.class).hasMessage("provider unavailable");
  }

  @Test
  void carriesItsCause() {
    Throwable cause = new IllegalStateException("boom");
    GenerationException exception = new GenerationException("wrapped", cause);
    assertThat(exception).hasMessage("wrapped").hasCause(cause);
  }
}
