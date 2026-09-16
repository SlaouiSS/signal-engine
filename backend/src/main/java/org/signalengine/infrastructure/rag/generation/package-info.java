/**
 * Infrastructure adapter for grounded RAG generation (docs/07-rag.md Section 26;
 * docs/adr/0015-rag-grounded-generator.md).
 *
 * <p>{@link org.signalengine.infrastructure.rag.generation.AiCapabilityAnswerGenerator} implements
 * the RAG core {@link org.signalengine.rag.generation.Generator}: it forwards the query and the
 * context passages to the {@code answer} Python capability through the Task 6A {@link
 * org.signalengine.application.ai.AiCapabilityInvoker}, then builds a {@link
 * org.signalengine.rag.generation.RagAnswer} whose citations carry provenance taken from the
 * context, never from the model. No HTTP client, no provider SDK, no PostgreSQL, no Jackson here
 * &mdash; the transport and serialisation belong to the Task 6A invoker.
 *
 * <p>The structural {@link org.signalengine.rag.generation.GroundingAnswerValidator} it wires
 * alongside lives in the framework-free RAG core; this package only composes the beans.
 */
package org.signalengine.infrastructure.rag.generation;
