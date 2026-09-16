package org.signalengine.rag.retrieval;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.query.Query;

/**
 * Required pipeline stage: find the passages most relevant to a {@link Query}.
 *
 * <p>The contract says nothing about <i>how</i>. A dense vector retriever, a sparse keyword
 * retriever, a hybrid retriever that fuses both, or a future custom strategy all implement this one
 * method. Implementations honour {@link Query#metadataFilters()} on a best-effort basis and record
 * what they did in {@link RetrievalResult#metadata()}.
 */
@FunctionalInterface
public interface Retriever {

  RetrievalResult retrieve(Query query);

  /** Identity and version of the concrete implementation; overridden by real retrievers. */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.RETRIEVER);
  }
}
