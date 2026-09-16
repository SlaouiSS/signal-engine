/**
 * Infrastructure adapters for RAG indexing persistence (docs/07-rag.md Section 6, 23;
 * docs/adr/0012-pgvector-persistence-and-indexing.md).
 *
 * <p>This is where PostgreSQL and pgvector live. The generic RAG core ({@code
 * org.signalengine.rag.indexing}) has neither.
 *
 * <ul>
 *   <li>{@link org.signalengine.infrastructure.rag.indexing.PgVectorIndexedPassageStore} implements
 *       the core {@link org.signalengine.rag.indexing.IndexedPassageStore} against the {@code
 *       rag_passage} and {@code rag_passage_embedding} tables (migration V11). It upserts on the
 *       deterministic passage id and the embedding-model natural key, so indexing is idempotent and
 *       a new model coexists with the old one. It uses {@code JdbcClient} directly for the {@code
 *       ::vector} cast and the {@code ON CONFLICT} upsert, which Spring Data JDBC repositories do
 *       not express cleanly; no JPA/Hibernate.
 *   <li>{@link org.signalengine.infrastructure.rag.indexing.RagIndexingConfiguration} wires a
 *       {@link org.signalengine.rag.indexing.StagedIndexingPipeline} from a chunker, the embedding
 *       model and the store. Nothing in the backend consumes the pipeline yet &mdash; there is no
 *       scheduler, no use case, no API. Connecting it to the Signal Engine processing pipeline is a
 *       later task.
 * </ul>
 */
package org.signalengine.infrastructure.rag.indexing;
