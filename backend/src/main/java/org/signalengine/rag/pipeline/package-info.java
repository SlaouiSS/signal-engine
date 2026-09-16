/**
 * Pipeline composition (docs/07-rag.md Section 21).
 *
 * <p>{@link org.signalengine.rag.pipeline.RagPipeline} is the entry point: run a query and get a
 * {@link org.signalengine.rag.execution.RagExecution}, or ask only for a {@link
 * org.signalengine.rag.context.Context}. {@link org.signalengine.rag.pipeline.StagedRagPipeline} is
 * the one supplied implementation &mdash; it holds the two required stages ({@link
 * org.signalengine.rag.retrieval.Retriever}, {@link org.signalengine.rag.context.ContextAssembler})
 * and any of the optional ones, and runs them in the fixed conceptual order. It is assembled
 * through a builder; it is not subclassed, and it is not a god-object &mdash; every behaviour lives
 * in a stage contract, not here.
 *
 * <p>Adding a capability that a stage contract already covers (another query rewrite, a reranker, a
 * context compressor, a grounding validator) means composing it into that stage &mdash; via the
 * stage's {@code andThen} or by supplying it to the builder &mdash; and changes no other stage and
 * not this class.
 */
package org.signalengine.rag.pipeline;
