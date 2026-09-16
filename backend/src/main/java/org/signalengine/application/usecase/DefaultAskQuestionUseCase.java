package org.signalengine.application.usecase;

import static org.signalengine.application.usecase.Guards.requireText;

import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.generation.RagAnswer;
import org.signalengine.rag.pipeline.RagPipeline;
import org.signalengine.rag.query.Query;

/**
 * Default implementation of {@link AskQuestionUseCase}.
 *
 * <p>Depends on the {@link RagPipeline} port only &mdash; never a concrete retriever, context
 * assembler, or generator (CLAUDE.md Section 9) &mdash; and runs it end to end with {@link
 * RagPipeline#execute(Query)}.
 */
public final class DefaultAskQuestionUseCase implements AskQuestionUseCase {

  private final RagPipeline ragPipeline;

  public DefaultAskQuestionUseCase(RagPipeline ragPipeline) {
    this.ragPipeline = ragPipeline;
  }

  @Override
  public RagAnswer askQuestion(String questionText, int topK) {
    requireText(questionText, "question");
    RagExecution execution = ragPipeline.execute(Query.of(questionText, topK));
    RagAnswer answer = execution.answer();
    if (answer == null) {
      // Would mean the pipeline was wired without a Generator (RagContextConfiguration always
      // configures one when RagGenerationConfiguration is active) — a configuration defect, not a
      // normal "insufficient evidence" outcome, which is instead RagAnswer.answered() == false.
      throw new IllegalStateException(
          "the RAG pipeline produced no answer because no generator is configured");
    }
    return answer;
  }
}
