package org.signalengine.rag.retrieval;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.query.Query;

/**
 * Optional pipeline stage: reorder or prune a {@link RetrievalResult} using a signal the retriever
 * did not apply &mdash; a cross-encoder relevance model, a diversity criterion, a recency boost.
 *
 * <p>Takes the query and the current result, returns a new result. Composable via {@link
 * #andThen(Reranker)}. A pipeline with no reranker passes the retrieval result straight to context
 * assembly, so adding or removing reranking touches no other stage.
 */
@FunctionalInterface
public interface Reranker {

  RetrievalResult rerank(Query query, RetrievalResult retrieved);

  /** Runs {@code this}, then feeds the result into {@code next}. */
  default Reranker andThen(Reranker next) {
    return (query, retrieved) -> next.rerank(query, rerank(query, retrieved));
  }

  /** Identity and version of the concrete implementation; overridden by real rerankers. */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.RERANKER);
  }
}
