/**
 * Infrastructure adapters for RAG chunking (docs/07-rag.md Section 21;
 * docs/adr/0010-indexing-foundation-and-semantic-chunking.md).
 *
 * <p>This package is where framework and third-party dependencies are allowed. The generic RAG core
 * ({@code org.signalengine.rag.chunking}) has neither.
 *
 * <ul>
 *   <li>{@link org.signalengine.infrastructure.rag.chunking.SemanticChunkerAdapter} implements the
 *       core {@link org.signalengine.rag.chunking.Chunker} on top of {@code
 *       io.github.slaouiss:semantic-chunker-core}. The library's types never cross back into the
 *       core &mdash; the adapter maps generic input in and generic {@link
 *       org.signalengine.rag.chunking.Passage passages} out.
 *   <li>{@link org.signalengine.infrastructure.rag.chunking.NormalizedTextDocumentExtractor} is the
 *       library {@code DocumentExtractor} for already-normalized plain-text / Markdown content
 *       &mdash; the smallest extractor for Signal Engine's needs, so the heavyweight {@code
 *       semantic-chunker-tika} module is not pulled in.
 *   <li>{@link org.signalengine.infrastructure.rag.chunking.AiCapabilityChunkingModel} is the
 *       library {@code ChunkingModel}: it reaches a language model only through the Task 6A {@link
 *       org.signalengine.application.ai.AiCapabilityInvoker} and the versioned {@code
 *       semantic-chunk-boundary} Python capability. No Spring AI, no direct provider call.
 *   <li>{@link org.signalengine.infrastructure.rag.chunking.SignalEngineIndexableContent} maps a
 *       Signal Engine {@code RawInformationItem} + {@code Source} into the generic {@link
 *       org.signalengine.rag.indexing.IndexableContent}. Business types stay here, out of the core.
 * </ul>
 *
 * <p>Task 8.2 wires the two chunkers as beans but nothing consumes them yet: there is no indexing
 * pipeline, no persistence, no scheduler and no API. Those are later tasks.
 */
package org.signalengine.infrastructure.rag.chunking;
