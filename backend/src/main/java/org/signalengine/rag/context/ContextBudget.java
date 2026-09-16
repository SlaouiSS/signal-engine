package org.signalengine.rag.context;

/**
 * A model-independent upper bound on how much passage text a {@link Context} may hold.
 *
 * <p>The unit is <b>characters</b>, deliberately: the RAG core carries no tokenizer, and a
 * character count is exact, deterministic and provider-neutral. It is not derived from any LLM
 * context window &mdash; a generator that renders the context for a specific model applies its own,
 * tighter limit on top (docs/07-rag.md Section 25). {@code maxCharacters} counts only the passage
 * text an assembler selects; the separators, headers and instructions a generator adds are the
 * generator's budget to manage.
 *
 * <p>{@link #DEFAULT_MAX_CHARACTERS} is a provisional working default, not a fixed decision
 * (docs/07-rag.md Section 7, 18). {@link #MAX_MAX_CHARACTERS} is a hard ceiling so a context can
 * never be unbounded.
 *
 * @param maxCharacters the character budget; between 1 and {@link #MAX_MAX_CHARACTERS}
 */
public record ContextBudget(int maxCharacters) {

  /**
   * Provisional default character budget. Comfortably holds the default {@link
   * org.signalengine.rag.query.Query#DEFAULT_TOP_K} passages of typical chunk size with headroom,
   * and is far below any current LLM context window. A deployment tunes it.
   */
  public static final int DEFAULT_MAX_CHARACTERS = 12_000;

  /** Hard upper bound, so a context is always bounded regardless of configuration. */
  public static final int MAX_MAX_CHARACTERS = 200_000;

  public ContextBudget {
    if (maxCharacters < 1 || maxCharacters > MAX_MAX_CHARACTERS) {
      throw new IllegalArgumentException(
          "maxCharacters must be between 1 and " + MAX_MAX_CHARACTERS + ", was " + maxCharacters);
    }
  }

  /** The provisional default budget. */
  public static ContextBudget ofDefault() {
    return new ContextBudget(DEFAULT_MAX_CHARACTERS);
  }

  /** A budget of an explicit number of characters. */
  public static ContextBudget ofCharacters(int maxCharacters) {
    return new ContextBudget(maxCharacters);
  }
}
