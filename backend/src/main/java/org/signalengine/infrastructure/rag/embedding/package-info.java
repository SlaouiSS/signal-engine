/**
 * Infrastructure adapter for RAG embedding (docs/07-rag.md Section 22;
 * docs/adr/0011-embedding-contract-and-local-model.md).
 *
 * <p>{@link org.signalengine.infrastructure.rag.embedding.AiCapabilityEmbeddingModel} implements
 * the RAG core {@link org.signalengine.rag.embedding.EmbeddingModel} by calling the {@code embed}
 * Python capability through the Task 6A {@link org.signalengine.application.ai.AiCapabilityInvoker}
 * &mdash; the same versioned, provider-independent transport every other AI capability uses
 * (docs/adr/0006-ai-java-python-foundation.md). No second HTTP client, no Spring AI, no direct
 * Ollama call. The concrete embedding model (Ollama {@code bge-m3}, an NVIDIA endpoint, &hellip;)
 * is the Python service's configuration; this adapter never names one.
 *
 * <p>Task 8.3A wires the model as a bean but nothing in the backend consumes it yet: there is no
 * indexing pipeline, no pgvector, no retriever. Those are later tasks.
 */
package org.signalengine.infrastructure.rag.embedding;
