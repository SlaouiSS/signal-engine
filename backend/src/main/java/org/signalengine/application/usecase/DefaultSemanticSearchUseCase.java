package org.signalengine.application.usecase;

import static org.signalengine.application.usecase.Guards.requireText;

import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.signalengine.rag.retrieval.Retriever;

/**
 * Default implementation of {@link SemanticSearchUseCase}.
 *
 * <p>Depends on the {@link Retriever} port only &mdash; never a concrete retriever implementation
 * (CLAUDE.md Section 9) &mdash; and performs no ranking, filtering, or budgeting of its own.
 */
public final class DefaultSemanticSearchUseCase implements SemanticSearchUseCase {

  private final Retriever retriever;

  public DefaultSemanticSearchUseCase(Retriever retriever) {
    this.retriever = retriever;
  }

  @Override
  public RetrievalResult search(String queryText, int topK) {
    requireText(queryText, "search query");
    return retriever.retrieve(Query.of(queryText, topK));
  }
}
