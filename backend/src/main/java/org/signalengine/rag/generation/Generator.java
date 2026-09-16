package org.signalengine.rag.generation;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.query.Query;

/**
 * Optional pipeline stage: produce a {@link RagAnswer} grounded in a {@link Context}.
 *
 * <p>Provider-independent by contract: an implementation receives a generic context and returns a
 * generic answer, and nothing about the LLM, provider or SDK leaks into this type. A pipeline with
 * no generator simply returns retrieval and context.
 */
@FunctionalInterface
public interface Generator {

  RagAnswer generate(Query query, Context context);

  /** Identity and version of the concrete implementation; overridden by real generators. */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.GENERATOR);
  }
}
