package org.signalengine.infrastructure.rag.context;

import org.signalengine.rag.context.ContextBudget;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for RAG context assembly (docs/adr/0014-rag-context-assembly.md).
 *
 * <p>Only the context budget is configurable here, and only in the generic, model-independent unit
 * the RAG core uses: characters. It is <b>not</b> an LLM context window &mdash; a future generator
 * applies its own, tighter model-specific limit on top. {@code maxContextCharacters} stays at the
 * provisional {@link ContextBudget#DEFAULT_MAX_CHARACTERS default} until retrieval and generation
 * are tuned against a real corpus (docs/07-rag.md Section 7, 18, 25).
 *
 * @param maxContextCharacters the character budget for the assembled context; 1..{@link
 *     ContextBudget#MAX_MAX_CHARACTERS}
 */
@ConfigurationProperties("signal-engine.rag.context")
public record RagContextProperties(@DefaultValue("12000") int maxContextCharacters) {

  public ContextBudget toBudget() {
    return ContextBudget.ofCharacters(maxContextCharacters);
  }
}
