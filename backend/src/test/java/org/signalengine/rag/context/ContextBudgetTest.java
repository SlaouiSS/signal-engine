package org.signalengine.rag.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The context budget value object: an explicit, validated, model-independent character bound. */
class ContextBudgetTest {

  @Test
  void defaultBudgetIsTheProvisionalDefault() {
    assertThat(ContextBudget.ofDefault().maxCharacters())
        .isEqualTo(ContextBudget.DEFAULT_MAX_CHARACTERS);
  }

  @Test
  void anExplicitBudgetIsCarried() {
    assertThat(ContextBudget.ofCharacters(500).maxCharacters()).isEqualTo(500);
  }

  @Test
  void rejectsANonPositiveBudget() {
    assertThatThrownBy(() -> ContextBudget.ofCharacters(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ContextBudget.ofCharacters(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsABudgetAboveTheHardCeiling() {
    assertThatThrownBy(() -> ContextBudget.ofCharacters(ContextBudget.MAX_MAX_CHARACTERS + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ContextBudget.ofCharacters(ContextBudget.MAX_MAX_CHARACTERS).maxCharacters())
        .isEqualTo(ContextBudget.MAX_MAX_CHARACTERS);
  }
}
