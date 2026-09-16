package org.signalengine.infrastructure.rag.indexing;

import java.util.Map;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.indexing.IndexedPassage;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.indexing.IndexingException;
import org.signalengine.rag.indexing.PassagePersistOutcome;
import org.signalengine.rag.provenance.Provenance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL/pgvector implementation of {@link IndexedPassageStore} (migration V11).
 *
 * <p>One {@code save} upserts the passage row (keyed by the deterministic passage id) and its
 * embedding row (keyed by passage id + embedding model), atomically. A repeat of the same passage
 * and model refreshes in place; a different chunker configuration or a different embedding model
 * produces new rows that coexist. Nothing is deleted, and the vector is never truncated or padded —
 * a dimension other than the column's is a hard failure.
 *
 * <p>{@code JdbcClient} rather than a Spring Data JDBC repository, because the {@code ::vector}
 * cast and the {@code ON CONFLICT ... RETURNING} upsert do not map cleanly through {@code @Table}
 * records. No JPA/Hibernate.
 */
@Repository
class PgVectorIndexedPassageStore implements IndexedPassageStore {

  /** The fixed dimension of the {@code rag_passage_embedding.embedding} column (migration V11). */
  static final int COLUMN_DIMENSION = 768;

  private static final String IMPLEMENTATION_ID = "pgvector-indexed-passage-store";

  private static final String UPSERT_PASSAGE =
      """
      INSERT INTO rag_passage (
          id, content_id, source_id, document_id, origin_uri, title,
          chunker_id, chunker_version, passage_ordinal, passage_text,
          provenance_attributes, metadata, updated_at)
      VALUES (
          :id, :contentId, :sourceId, :documentId, :originUri, :title,
          :chunkerId, :chunkerVersion, :ordinal, :text,
          CAST(:provenanceAttributes AS jsonb), CAST(:metadata AS jsonb), now())
      ON CONFLICT (id) DO UPDATE SET
          content_id = excluded.content_id,
          source_id = excluded.source_id,
          document_id = excluded.document_id,
          origin_uri = excluded.origin_uri,
          title = excluded.title,
          chunker_id = excluded.chunker_id,
          chunker_version = excluded.chunker_version,
          passage_ordinal = excluded.passage_ordinal,
          passage_text = excluded.passage_text,
          provenance_attributes = excluded.provenance_attributes,
          metadata = excluded.metadata,
          updated_at = now()
      """;

  private static final String UPSERT_EMBEDDING =
      """
      INSERT INTO rag_passage_embedding (
          passage_id, embedding_provider, embedding_model, embedding_model_version,
          embedding_dimension, embedding, updated_at)
      VALUES (
          :passageId, :provider, :model, :version, :dimension, CAST(:vector AS vector), now())
      ON CONFLICT (passage_id, embedding_model, embedding_model_version) DO UPDATE SET
          embedding_provider = excluded.embedding_provider,
          embedding_dimension = excluded.embedding_dimension,
          embedding = excluded.embedding,
          updated_at = now()
      RETURNING (xmax = 0) AS inserted
      """;

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  PgVectorIndexedPassageStore(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public org.signalengine.rag.ComponentDescriptor descriptor() {
    return new org.signalengine.rag.ComponentDescriptor(
        org.signalengine.rag.RagComponentType.INDEXED_PASSAGE_STORE,
        IMPLEMENTATION_ID,
        "v11;dim=" + COLUMN_DIMENSION);
  }

  @Override
  @Transactional
  public PassagePersistOutcome save(IndexedPassage indexed) {
    if (indexed == null) {
      throw new IllegalArgumentException("indexed passage must not be null");
    }
    float[] vector = indexed.embedding();
    if (vector.length != COLUMN_DIMENSION) {
      throw new IndexingException(
          "embedding dimension "
              + vector.length
              + " is incompatible with this store's vector column ("
              + COLUMN_DIMENSION
              + "); a different-dimension model needs a new migration");
    }

    Passage passage = indexed.passage();
    Provenance provenance = passage.provenance();
    jdbcClient
        .sql(UPSERT_PASSAGE)
        .param("id", passage.passageId())
        .param("contentId", indexed.contentId())
        .param("sourceId", provenance.sourceId())
        .param("documentId", provenance.documentId())
        .param(
            "originUri", provenance.originUri() == null ? null : provenance.originUri().toString())
        .param("title", provenance.title())
        .param("chunkerId", indexed.chunker().implementationId())
        .param("chunkerVersion", indexed.chunker().version())
        .param("ordinal", passage.ordinal())
        .param("text", passage.text())
        .param("provenanceAttributes", toJson(provenance.attributes()))
        .param("metadata", toJson(passage.metadata()))
        .update();

    EmbeddingModelDescriptor model = indexed.embeddingModel();
    boolean inserted =
        Boolean.TRUE.equals(
            jdbcClient
                .sql(UPSERT_EMBEDDING)
                .param("passageId", passage.passageId())
                .param("provider", model.provider())
                .param("model", model.model())
                .param("version", model.version())
                .param("dimension", vector.length)
                .param("vector", toPgVectorLiteral(vector))
                .query(Boolean.class)
                .single());

    return inserted ? PassagePersistOutcome.INSERTED : PassagePersistOutcome.UPDATED;
  }

  private String toJson(Map<String, String> map) {
    try {
      return objectMapper.writeValueAsString(map);
    } catch (JacksonException notSerialisable) {
      throw new IndexingException("passage metadata is not serialisable to JSON", notSerialisable);
    }
  }

  private static String toPgVectorLiteral(float[] vector) {
    StringBuilder builder = new StringBuilder(vector.length * 12).append('[');
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(Float.toString(vector[i]));
    }
    return builder.append(']').toString();
  }
}
