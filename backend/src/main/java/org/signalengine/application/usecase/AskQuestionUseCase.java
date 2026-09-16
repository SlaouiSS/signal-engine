package org.signalengine.application.usecase;

import org.signalengine.rag.generation.RagAnswer;

/**
 * Ask a natural-language question grounded in the knowledge base (docs/02-functional-spec.md
 * Section 13, workflow W10).
 *
 * <p>Delegates to the already-built {@link org.signalengine.rag.pipeline.RagPipeline}: retrieval,
 * context assembly, generation, and grounding validation are not reimplemented here. When the
 * retrieved content does not support an answer, the pipeline's {@code answered = false} outcome is
 * returned as-is rather than treated as an error (docs/02-functional-spec.md Section 13.3, "No
 * supporting knowledge"). Citations are never manufactured by this use case &mdash; they are
 * exactly the {@link org.signalengine.rag.provenance.Citation}s the pipeline attached from the
 * retrieved context.
 */
public interface AskQuestionUseCase {

  /**
   * @param questionText the natural-language question; must not be blank
   * @param topK how many passages the pipeline should retrieve to ground the answer; between 1 and
   *     {@link org.signalengine.rag.query.Query#MAX_TOP_K}
   * @return the grounded answer (which may explicitly report insufficient evidence)
   * @throws org.signalengine.rag.embedding.EmbeddingException if retrieval could not run
   * @throws org.signalengine.rag.generation.GenerationException if no trustworthy answer could be
   *     produced at all (docs/02-functional-spec.md Section 13.3, "AI or retrieval unavailable")
   */
  RagAnswer askQuestion(String questionText, int topK);
}
