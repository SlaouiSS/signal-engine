package org.signalengine.rag.query;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;

/**
 * Optional pipeline stage: transform the incoming {@link Query} before retrieval &mdash; rewriting,
 * expansion, translation, spelling correction, or inferring {@link Query#metadataFilters() metadata
 * filters}.
 *
 * <p>Composable: {@link #andThen(QueryProcessor)} chains processors so a new transformation is
 * added by wrapping the existing one, never by editing it. A pipeline with no query processor
 * passes the original query straight to the {@link org.signalengine.rag.retrieval.Retriever}.
 */
@FunctionalInterface
public interface QueryProcessor {

  Query process(Query query);

  /** Runs {@code this}, then feeds the result into {@code next}. */
  default QueryProcessor andThen(QueryProcessor next) {
    return query -> next.process(process(query));
  }

  /** Identity and version of the concrete implementation; overridden by real processors. */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.QUERY_PROCESSOR);
  }
}
