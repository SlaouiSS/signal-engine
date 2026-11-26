package org.signalengine.infrastructure.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Chunker;
import org.signalengine.rag.chunking.Chunking;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.indexing.IndexableContent;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.indexing.StagedIndexingPipeline;
import org.signalengine.rag.provenance.Provenance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * The SQL limits reached in the middle of an atomic replacement, through the real transactional
 * store: nothing partial remains and the previous index is intact. A trigger makes chosen passages
 * slow; the limits are shortened so they can be reached.
 */
@TestPropertySource(
    properties =
        "spring.datasource.hikari.connection-init-sql="
            + "SET statement_timeout = '2s'; SET lock_timeout = '1s';"
            + " SET transaction_timeout = '3s'")
class ReplacementTimeoutIntegrationTest extends AbstractPersistenceIntegrationTest {

  private static final ComponentDescriptor CHUNKER =
      new ComponentDescriptor(RagComponentType.CHUNKER, "timeout-probe-chunker", "v1");

  @Autowired private IndexedPassageStore store;
  @Autowired private JdbcClient jdbcClient;

  private final String contentId = "timeout-" + UUID.randomUUID();

  @BeforeEach
  void slowDownChosenPassages() {
    jdbcClient
        .sql(
            """
            CREATE OR REPLACE FUNCTION slow_probe_passage() RETURNS trigger AS $$
            BEGIN
              IF NEW.passage_text LIKE 'slow-transaction%' THEN PERFORM pg_sleep(0.5); END IF;
              IF NEW.passage_text LIKE 'slow-statement%' THEN PERFORM pg_sleep(4); END IF;
              RETURN NEW;
            END $$ LANGUAGE plpgsql
            """)
        .update();
    jdbcClient
        .sql(
            "CREATE OR REPLACE TRIGGER slow_probe BEFORE INSERT OR UPDATE ON rag_passage"
                + " FOR EACH ROW EXECUTE FUNCTION slow_probe_passage()")
        .update();
  }

  @AfterEach
  void removeTheTrigger() {
    jdbcClient.sql("DROP TRIGGER IF EXISTS slow_probe ON rag_passage").update();
  }

  @Test
  void aStatementTimeoutDuringAReplacementLeavesThePreviousIndexUntouched() {
    index("fingerprint-1", List.of("first", "second"));
    Object previousFingerprint = marker().get("content_fingerprint");

    assertThatThrownBy(
            () -> index("fingerprint-2", List.of("new one", "new two", "slow-statement")))
        .hasStackTraceContaining("statement timeout");

    assertThePreviousIndexIsIntact(previousFingerprint);
  }

  @Test
  void aTransactionTimeoutAfterManyShortStatementsLeavesThePreviousIndexUntouched() {
    index("fingerprint-1", List.of("first", "second"));
    Object previousFingerprint = marker().get("content_fingerprint");
    List<String> manySlow = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      manySlow.add("slow-transaction " + i);
    }

    assertThatThrownBy(() -> index("fingerprint-2", manySlow)).isInstanceOf(RuntimeException.class);

    assertThePreviousIndexIsIntact(previousFingerprint);
  }

  private Map<String, Object> marker() {
    return jdbcClient
        .sql("SELECT * FROM rag_content_index WHERE content_id = :id")
        .param("id", contentId)
        .query()
        .singleRow();
  }

  private void assertThePreviousIndexIsIntact(Object previousFingerprint) {
    assertThat(storedTexts()).containsExactlyInAnyOrder("first", "second");
    Map<String, Object> marker = marker();
    assertThat(marker.get("content_fingerprint")).isEqualTo(previousFingerprint);
    assertThat(marker.get("passage_count")).isEqualTo(2);
    assertThat(marker.get("lease_until")).as("the claim was released").isNull();
    assertThat(marker.get("unexpected_failures")).as("and the failure counted").isEqualTo(1);
  }

  private List<String> storedTexts() {
    return jdbcClient
        .sql("SELECT passage_text FROM rag_passage WHERE content_id = :id")
        .param("id", contentId)
        .query(String.class)
        .list();
  }

  private void index(String fingerprint, List<String> texts) {
    Chunker chunker =
        new Chunker() {
          @Override
          public ComponentDescriptor descriptor() {
            return CHUNKER;
          }

          @Override
          public Chunking chunk(IndexableContent content) {
            List<Passage> passages = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
              String id = contentId + "-" + fingerprint + "-" + i;
              passages.add(
                  new Passage(
                      id,
                      i,
                      texts.get(i),
                      new Provenance("src", null, "Title", contentId, id, Map.of()),
                      Map.of()));
            }
            return new Chunking(contentId, passages, CHUNKER, List.of(), Map.of());
          }
        };
    EmbeddingModel embedding =
        request -> {
          List<float[]> vectors = new ArrayList<>();
          for (int i = 0; i < request.texts().size(); i++) {
            float[] vector = new float[768];
            vector[i % 768] = 1;
            vectors.add(vector);
          }
          return new EmbeddingResult(
              vectors, 768, new EmbeddingModelDescriptor("stub", "stub-768", "test", 768));
        };
    StagedIndexingPipeline.builder()
        .chunker(chunker)
        .embeddingModel(embedding)
        .store(store)
        .failureClassifier(RagIndexingConfiguration::classify)
        .build()
        .index(
            new IndexableContent(
                contentId,
                fingerprint,
                new Provenance("src", null, "Title", contentId, null, Map.of()),
                Map.of()));
  }
}
