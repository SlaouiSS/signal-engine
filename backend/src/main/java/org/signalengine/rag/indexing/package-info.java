/**
 * Indexing &mdash; turning content into stored, embedded, retrievable passages (docs/07-rag.md
 * Section 13, 21, 23; docs/adr/0012-pgvector-persistence-and-indexing.md).
 *
 * <pre>
 *   IndexableContent → Chunker → passages → EmbeddingModel → IndexedPassageStore → IndexingReport
 * </pre>
 *
 * <p>Still framework-free and business-free: no Spring, no JDBC, no PostgreSQL, no pgvector, no
 * Signal Engine business concept. The store is a generic port &mdash; the write-side counterpart of
 * {@code Retriever} &mdash; and a PostgreSQL/pgvector adapter implements it in the infrastructure
 * layer.
 *
 * <ul>
 *   <li>{@link org.signalengine.rag.indexing.IndexingPipeline} &mdash; {@code IndexingReport
 *       index(IndexableContent)}. {@link org.signalengine.rag.indexing.StagedIndexingPipeline} is
 *       the supplied composition of a chunker, an embedding model and a store.
 *   <li>{@link org.signalengine.rag.indexing.IndexedPassage} &mdash; a chunked {@link
 *       org.signalengine.rag.chunking.Passage} plus its embedding, the chunker descriptor and the
 *       {@link org.signalengine.rag.embedding.EmbeddingModelDescriptor}. Its identity is the
 *       deterministic passage id plus the embedding model &mdash; the basis for idempotent storage.
 *   <li>{@link org.signalengine.rag.indexing.IndexedPassageStore} &mdash; the generic write port:
 *       claim a content, prepare outside any transaction, replace atomically with a completion
 *       marker (docs/adr/0018-pipeline-reliability.md). Idempotent by identity; a changed chunker
 *       configuration or a changed embedding model coexists rather than overwriting; only the
 *       passages of the same content and chunker configuration that a new result no longer contains
 *       are removed; nothing is truncated or padded.
 *   <li>{@link org.signalengine.rag.indexing.IndexingReport} &mdash; passages, embedded, persisted,
 *       skipped, failed, plus notes; failures are explicit here, never swallowed.
 * </ul>
 *
 * <p>Retrieval, query embedding, ranking and scoring are <b>not</b> here.
 */
package org.signalengine.rag.indexing;
