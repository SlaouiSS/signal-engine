/**
 * Embedding &mdash; turning text into vectors (docs/07-rag.md Section 5, 22; docs/06-ai-agents.md
 * Section 4.6; docs/adr/0011-embedding-contract-and-local-model.md).
 *
 * <pre>
 *   EmbeddingRequest { text(s), role }  →  EmbeddingModel  →  EmbeddingResult { vector(s), dimension, model }
 * </pre>
 *
 * <p>Still framework-free and business-free: no Spring, no Ollama or NVIDIA type, no Spring AI, no
 * PostgreSQL / pgvector, no Signal Engine business concept. A future project can embed text through
 * {@link org.signalengine.rag.embedding.EmbeddingModel} and swap Ollama &rarr; NVIDIA &rarr;
 * another runtime without touching this package or anything that depends on it.
 *
 * <ul>
 *   <li>{@link org.signalengine.rag.embedding.EmbeddingModel} is the one contract: {@code
 *       EmbeddingResult embed(EmbeddingRequest)}.
 *   <li>{@link org.signalengine.rag.embedding.TextRole} distinguishes a {@code QUERY} from a {@code
 *       PASSAGE} so a model that documents asymmetric retrieval is used correctly; the core does
 *       not know which prefixes, if any, a given model needs.
 *   <li>{@link org.signalengine.rag.embedding.EmbeddingResult} validates what an embedding must
 *       satisfy &mdash; a non-empty vector per input, all vectors the same finite-valued dimension,
 *       output order matching input order &mdash; and never silently truncates or pads.
 *   <li>{@link org.signalengine.rag.embedding.EmbeddingException} is the one failure type: a
 *       provider failure, a timeout, or an invalid result.
 * </ul>
 *
 * <p>This package does <b>not</b> compute similarity, rank, retrieve, or persist. pgvector, the
 * retriever, and the final production embedding model are later decisions.
 */
package org.signalengine.rag.embedding;
