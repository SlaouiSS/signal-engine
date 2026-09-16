package org.signalengine.rag.context;

import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievalResult;

/**
 * Required pipeline stage: select and order retrieved passages into a {@link Context}.
 *
 * <p>This is where a context budget, per-source limits, ordering strategy and passage selection
 * live. The assembler must preserve each selected passage's {@link
 * org.signalengine.rag.provenance.Provenance} and identifier so the context stays traceable. It
 * must not turn the context into a prompt string &mdash; formatting is the generator's job.
 */
@FunctionalInterface
public interface ContextAssembler {

  Context assemble(Query query, RetrievalResult retrieved);

  /** Identity and version of the concrete implementation; overridden by real assemblers. */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.CONTEXT_ASSEMBLER);
  }
}
