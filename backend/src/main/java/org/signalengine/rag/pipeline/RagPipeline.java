package org.signalengine.rag.pipeline;

import org.signalengine.rag.context.Context;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.query.Query;

/**
 * Runs a {@link Query} through the RAG pipeline.
 *
 * <p>Two entry points, because {@link Context} is a first-class output:
 *
 * <ul>
 *   <li>{@link #execute(Query)} runs the whole configured pipeline and returns a {@link
 *       RagExecution}. If no generator is configured, the execution carries a retrieval result and
 *       a context but no answer.
 *   <li>{@link #assembleContext(Query)} runs query processing, retrieval, reranking, context
 *       assembly and refinement and stops &mdash; no generator is invoked even when one is
 *       configured.
 * </ul>
 */
public interface RagPipeline {

  RagExecution execute(Query query);

  Context assembleContext(Query query);
}
