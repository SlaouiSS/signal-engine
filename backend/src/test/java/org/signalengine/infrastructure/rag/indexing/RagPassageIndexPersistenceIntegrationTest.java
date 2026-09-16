package org.signalengine.infrastructure.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.indexing.IndexedPassage;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.indexing.IndexingException;
import org.signalengine.rag.indexing.PassagePersistOutcome;
import org.signalengine.rag.provenance.Provenance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The pgvector passage index against a real PostgreSQL 17 + pgvector (migration V11): a 768-d
 * vector round-trips, indexing is idempotent, provenance/metadata/model identity are preserved, and
 * the constraints behave.
 */
class RagPassageIndexPersistenceIntegrationTest extends AbstractPersistenceIntegrationTest {

  private static final ComponentDescriptor CHUNKER =
      new ComponentDescriptor(
          RagComponentType.CHUNKER, "structure-aware-chunker", "1;maxCharsPerPassage=1200");
  private static final EmbeddingModelDescriptor GEMMA =
      new EmbeddingModelDescriptor("ollama", "embeddinggemma", "embed/v1", 768);

  @Autowired private IndexedPassageStore store;
  @Autowired private JdbcClient jdbcClient;

  private static float[] vector(int seed) {
    float[] v = new float[768];
    for (int i = 0; i < v.length; i++) {
      v[i] = (float) Math.sin((seed + 1) * 0.001 * (i + 1));
    }
    return v;
  }

  private static IndexedPassage passage(
      String id, String contentId, int ordinal, EmbeddingModelDescriptor model, float[] embedding) {
    Provenance provenance =
        new Provenance(
            "reuters",
            java.net.URI.create("https://example.test/a/" + id),
            "Article " + id,
            "doc-" + contentId,
            id,
            Map.of("author", "A. Writer"));
    Passage p =
        new Passage(id, ordinal, "passage text for " + id, provenance, Map.of("language", "en"));
    return new IndexedPassage(contentId, p, CHUNKER, embedding, model);
  }

  @Test
  void theEmbeddingColumnIsVector768() {
    Integer typmod =
        jdbcClient
            .sql(
                "SELECT atttypmod FROM pg_attribute"
                    + " WHERE attrelid = 'rag_passage_embedding'::regclass AND attname = 'embedding'")
            .query(Integer.class)
            .single();
    assertThat(typmod).isEqualTo(768);
  }

  @Test
  void aRealEmbeddingRoundTripsAndReIndexingIsIdempotent() {
    IndexedPassage passage = passage("rt-1", "content-rt", 0, GEMMA, vector(1));

    assertThat(store.save(passage)).isEqualTo(PassagePersistOutcome.INSERTED);
    assertThat(store.save(passage)).isEqualTo(PassagePersistOutcome.UPDATED);

    Integer passageRows =
        jdbcClient
            .sql("SELECT count(*) FROM rag_passage WHERE id = 'rt-1'")
            .query(Integer.class)
            .single();
    Integer embeddingRows =
        jdbcClient
            .sql("SELECT count(*) FROM rag_passage_embedding WHERE passage_id = 'rt-1'")
            .query(Integer.class)
            .single();
    Integer dims =
        jdbcClient
            .sql(
                "SELECT vector_dims(embedding) FROM rag_passage_embedding WHERE passage_id = 'rt-1'")
            .query(Integer.class)
            .single();

    assertThat(passageRows).isEqualTo(1);
    assertThat(embeddingRows).isEqualTo(1);
    assertThat(dims).isEqualTo(768);
  }

  @Test
  void provenanceMetadataAndModelIdentityArePreserved() {
    store.save(passage("pm-1", "content-pm", 3, GEMMA, vector(2)));

    var passageRow =
        jdbcClient
            .sql(
                "SELECT content_id, source_id, document_id, origin_uri, title, chunker_id,"
                    + " chunker_version, passage_ordinal, provenance_attributes->>'author' AS author,"
                    + " metadata->>'language' AS lang FROM rag_passage WHERE id = 'pm-1'")
            .query(
                (rs, n) ->
                    new String[] {
                      rs.getString("content_id"),
                      rs.getString("source_id"),
                      rs.getString("document_id"),
                      rs.getString("origin_uri"),
                      rs.getString("title"),
                      rs.getString("chunker_id"),
                      rs.getString("chunker_version"),
                      Integer.toString(rs.getInt("passage_ordinal")),
                      rs.getString("author"),
                      rs.getString("lang")
                    })
            .single();

    assertThat(passageRow)
        .containsExactly(
            "content-pm",
            "reuters",
            "doc-content-pm",
            "https://example.test/a/pm-1",
            "Article pm-1",
            "structure-aware-chunker",
            "1;maxCharsPerPassage=1200",
            "3",
            "A. Writer",
            "en");

    var embeddingRow =
        jdbcClient
            .sql(
                "SELECT embedding_provider, embedding_model, embedding_model_version, embedding_dimension"
                    + " FROM rag_passage_embedding WHERE passage_id = 'pm-1'")
            .query(
                (rs, n) ->
                    new String[] {
                      rs.getString("embedding_provider"),
                      rs.getString("embedding_model"),
                      rs.getString("embedding_model_version"),
                      Integer.toString(rs.getInt("embedding_dimension"))
                    })
            .single();
    assertThat(embeddingRow).containsExactly("ollama", "embeddinggemma", "embed/v1", "768");
  }

  @Test
  void twoEmbeddingModelsCoexistForTheSamePassage() {
    EmbeddingModelDescriptor other =
        new EmbeddingModelDescriptor("ollama", "granite-embedding", "embed/v1", 768);
    store.save(passage("co-1", "content-co", 0, GEMMA, vector(3)));
    store.save(passage("co-1", "content-co", 0, other, vector(4)));

    Integer passageRows =
        jdbcClient
            .sql("SELECT count(*) FROM rag_passage WHERE id = 'co-1'")
            .query(Integer.class)
            .single();
    Integer embeddingRows =
        jdbcClient
            .sql("SELECT count(*) FROM rag_passage_embedding WHERE passage_id = 'co-1'")
            .query(Integer.class)
            .single();
    assertThat(passageRows).isEqualTo(1);
    assertThat(embeddingRows).isEqualTo(2);
  }

  @Test
  void aWrongDimensionEmbeddingIsRejectedBeforeItReachesTheColumn() {
    IndexedPassage tooShort =
        passage(
            "bad-1",
            "content-bad",
            0,
            new EmbeddingModelDescriptor("ollama", "m", "v", 8),
            new float[8]);
    assertThatThrownBy(() -> store.save(tooShort))
        .isInstanceOf(IndexingException.class)
        .hasMessageContaining("incompatible");
  }

  @Test
  void theForeignKeyIsEnforced() {
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        "INSERT INTO rag_passage_embedding"
                            + " (passage_id, embedding_provider, embedding_model, embedding_model_version,"
                            + " embedding_dimension, embedding)"
                            + " VALUES ('does-not-exist', 'ollama', 'm', 'v', 768, :vec::vector)")
                    .param("vec", vectorLiteral(vector(9)))
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void theEmbeddingDimensionCheckIsEnforced() {
    store.save(passage("ck-1", "content-ck", 0, GEMMA, vector(11)));
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(
                        "INSERT INTO rag_passage_embedding"
                            + " (passage_id, embedding_provider, embedding_model, embedding_model_version,"
                            + " embedding_dimension, embedding)"
                            + " VALUES ('ck-1', 'ollama', 'other', 'v', 512, :vec::vector)")
                    .param("vec", vectorLiteral(vector(12)))
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deletingAPassageCascadesToItsEmbeddings() {
    store.save(passage("cx-1", "content-cx", 0, GEMMA, vector(5)));
    jdbcClient.sql("DELETE FROM rag_passage WHERE id = 'cx-1'").update();
    Integer embeddingRows =
        jdbcClient
            .sql("SELECT count(*) FROM rag_passage_embedding WHERE passage_id = 'cx-1'")
            .query(Integer.class)
            .single();
    assertThat(embeddingRows).isZero();
  }

  @Test
  void noApproximateVectorIndexExistsYet() {
    Integer annIndexes =
        jdbcClient
            .sql(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'rag_passage_embedding'"
                    + " AND (indexdef ILIKE '%hnsw%' OR indexdef ILIKE '%ivfflat%')")
            .query(Integer.class)
            .single();
    assertThat(annIndexes).isZero(); // deferred to T7, documented in V11
  }

  private static String vectorLiteral(float[] v) {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < v.length; i++) {
      if (i > 0) {
        b.append(',');
      }
      b.append(Float.toString(v[i]));
    }
    return b.append(']').toString();
  }
}
