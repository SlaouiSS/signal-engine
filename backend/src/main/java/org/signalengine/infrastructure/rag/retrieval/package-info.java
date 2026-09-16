/**
 * Infrastructure adapter for semantic retrieval (docs/07-rag.md Section 7, 24;
 * docs/adr/0013-semantic-retrieval-pgvector.md).
 *
 * <p>{@link org.signalengine.infrastructure.rag.retrieval.PgVectorRetriever} implements the RAG
 * core {@link org.signalengine.rag.retrieval.Retriever}: it embeds the query through the generic
 * {@link org.signalengine.rag.embedding.EmbeddingModel} (Task 8.3A, reaching Python's {@code embed}
 * capability), then runs an <b>exact</b> cosine nearest-neighbour scan over {@code
 * rag_passage_embedding} (migration V11) with {@code JdbcClient} &mdash; no ANN index (T7 stays
 * open), no new table, no second vector store. The query is only compared against embeddings from
 * the <i>same</i> model identity (provider + model + version + dimension), so coexisting models are
 * never mixed.
 *
 * <p>PostgreSQL, pgvector and JDBC stay in this package; the RAG core never sees them.
 *
 * <p>Task 8.3C wires the retriever as a bean; Tasks 8.4&ndash;8.5 compose it with the {@link
 * org.signalengine.rag.context.ContextAssembler} and the {@link
 * org.signalengine.rag.generation.Generator} into a query-time {@link
 * org.signalengine.rag.pipeline.RagPipeline}. Nothing in the backend consumes that pipeline yet
 * &mdash; there is no query API.
 */
package org.signalengine.infrastructure.rag.retrieval;
