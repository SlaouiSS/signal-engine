package org.signalengine.application.usecase;

import org.signalengine.rag.retrieval.RetrievalResult;

/**
 * Search the knowledge base by meaning (docs/02-functional-spec.md Section 12.3, workflow W9).
 *
 * <p>Delegates to the already-built retrieval capability (docs/07-rag.md; docs/adr/0013) rather
 * than reimplementing ranking: this is a thin read path over the {@link
 * org.signalengine.rag.retrieval.Retriever} port. No reranking, filtering by area/interest/time, or
 * pagination is applied — none of those exist at the retrieval layer yet (docs/07-rag.md Section 8,
 * 24; the functional-spec filters of W9 remain open until the retrieval layer supports them).
 */
public interface SemanticSearchUseCase {

  /**
   * @param queryText the search phrase; must not be blank
   * @param topK how many passages to return; between 1 and {@link
   *     org.signalengine.rag.query.Query#MAX_TOP_K}
   * @return the ranked passages, most relevant first; empty when nothing matched (never an error)
   */
  RetrievalResult search(String queryText, int topK);
}
