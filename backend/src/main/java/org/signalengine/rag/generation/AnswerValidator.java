package org.signalengine.rag.generation;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.query.Query;

/**
 * Optional pipeline stage: check a {@link RagAnswer} against the {@link Context} it was generated
 * from and return a possibly-corrected answer &mdash; dropping citations that do not resolve to a
 * context passage, marking an answer as unsupported, or removing unsupported claims.
 *
 * <p>Runs only when a {@link Generator} ran. Composable via {@link #andThen(AnswerValidator)} so
 * grounding validation, citation validation and answer validation stack without rewriting each
 * other. This is a runtime control; measuring answer quality after the fact is {@link
 * org.signalengine.rag.evaluation}'s job, not this stage's.
 */
@FunctionalInterface
public interface AnswerValidator {

  RagAnswer validate(Query query, Context context, RagAnswer answer);

  /** Runs {@code this}, then feeds the result into {@code next}. */
  default AnswerValidator andThen(AnswerValidator next) {
    return (query, context, answer) ->
        next.validate(query, context, validate(query, context, answer));
  }

  /** Identity and version of the concrete implementation; overridden by real validators. */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.ANSWER_VALIDATOR);
  }
}
