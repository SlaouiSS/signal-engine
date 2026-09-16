package org.signalengine.rag.context;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.query.Query;

/**
 * Optional pipeline stage: transform an already-assembled {@link Context} &mdash; compress passage
 * text, drop near-redundant passages, diversify, or re-cap to a tighter budget.
 *
 * <p>Takes the query and the current context, returns a new context. Composable via {@link
 * #andThen(ContextRefiner)}. Because it operates <i>after</i> assembly, a new refinement is added
 * without changing the {@link ContextAssembler}. Provenance and passage identifiers must survive
 * refinement so the context stays traceable.
 */
@FunctionalInterface
public interface ContextRefiner {

  Context refine(Query query, Context context);

  /** Runs {@code this}, then feeds the result into {@code next}. */
  default ContextRefiner andThen(ContextRefiner next) {
    return (query, context) -> next.refine(query, refine(query, context));
  }

  /** Identity and version of the concrete implementation; overridden by real refiners. */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.CONTEXT_REFINER);
  }
}
