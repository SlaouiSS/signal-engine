package org.signalengine.interfaces.rest.question;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.signalengine.rag.query.Query;

/**
 * Body for a grounded question-answering request (docs/02-functional-spec.md Section 13, workflow
 * W10). {@code topK} is optional; omitting it uses {@link Query#DEFAULT_TOP_K}. Multi-turn
 * conversation history is out of scope (Q21, open) and not accepted here.
 */
record QuestionRequest(@NotBlank String question, @Min(1) @Max(Query.MAX_TOP_K) Integer topK) {

  int topKOrDefault() {
    return topK == null ? Query.DEFAULT_TOP_K : topK;
  }
}
