/**
 * Chunking &mdash; turning one unit of normalized content into ordered, provenance-bearing {@link
 * org.signalengine.rag.chunking.Passage passages} (docs/07-rag.md Section 21;
 * docs/adr/0010-indexing-foundation-and-semantic-chunking.md).
 *
 * <pre>
 *   IndexableContent  &rarr;  Chunker  &rarr;  Chunking { ordered Passages + which chunker produced them }
 * </pre>
 *
 * <p>This package is the first real brick of the indexing side of RAG. It is still framework-free
 * and business-free: no Spring, no {@code io.github.semanticchunker} type, no Signal Engine
 * business concept. A future project can chunk content through {@link
 * org.signalengine.rag.chunking.Chunker} without importing anything else.
 *
 * <ul>
 *   <li>{@link org.signalengine.rag.chunking.Chunker} is the one contract. It accepts a generic
 *       {@link org.signalengine.rag.indexing.IndexableContent} and returns a {@link
 *       org.signalengine.rag.chunking.Chunking}. Fixed-size, recursive, Markdown-aware, HTML-aware,
 *       semantic, or custom strategies all implement it; none of their configuration or library
 *       types appear on the contract.
 *   <li>{@link org.signalengine.rag.chunking.StructureAwareChunker} is a dependency-free reference
 *       implementation: a deterministic structural baseline, <b>not</b> a semantic chunker. The
 *       semantic chunker (backed by {@code SlaouiSS/semantic-chunker}) lives behind the same
 *       contract in the infrastructure layer.
 *   <li>{@link org.signalengine.rag.chunking.Passage} carries a deterministic {@code passageId},
 *       its position, its text, its {@link org.signalengine.rag.provenance.Provenance} (carried
 *       through unchanged), and open metadata. It is a retrieval/indexing unit &mdash; not a
 *       business entity, not a document, not an embedding, not a retrieval result.
 *   <li>{@link org.signalengine.rag.chunking.Chunking} names, via a {@link
 *       org.signalengine.rag.ComponentDescriptor}, exactly which chunker and configuration produced
 *       the passages, so a future evaluator can compare configuration A against configuration B on
 *       the same corpus.
 * </ul>
 *
 * <p>Embeddings, vector storage, retrieval and persistence are <b>not</b> here and are not decided
 * by this package.
 */
package org.signalengine.rag.chunking;
