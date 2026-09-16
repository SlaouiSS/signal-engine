package org.signalengine.interfaces.rest.question;

import java.util.List;
import org.signalengine.rag.generation.RagAnswer;

/**
 * API representation of a grounded answer (docs/02-functional-spec.md Section 13.2, W10).
 *
 * <p>{@code answered = false} is the explicit "not enough information in the knowledge base"
 * outcome (W10 "No supporting knowledge") &mdash; not an error. {@code citations} is empty only
 * when {@code answered} is {@code false}.
 */
public record QuestionResponse(boolean answered, String answer, List<CitationResponse> citations) {

  public static QuestionResponse from(RagAnswer ragAnswer) {
    return new QuestionResponse(
        ragAnswer.answered(),
        ragAnswer.text(),
        ragAnswer.citations().stream().map(CitationResponse::from).toList());
  }
}
