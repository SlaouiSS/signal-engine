package org.signalengine.infrastructure.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.indexing.IndexClaim;
import org.signalengine.rag.indexing.IndexedPassage;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.provenance.Provenance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The largest content a source can deliver (5 MB, some 4,500 passages) is replaced atomically
 * within the real SQL limits, since embedding it in batches no longer caps its number of passages.
 */
class LargeReplacementIntegrationTest extends AbstractPersistenceIntegrationTest {

  private static final ComponentDescriptor CHUNKER =
      new ComponentDescriptor(RagComponentType.CHUNKER, "large-probe-chunker", "v1");

  @Autowired private IndexedPassageStore store;
  @Autowired private JdbcClient jdbcClient;

  @Test
  void fourThousandFiveHundredPassagesFitWellWithinTheTransactionLimit() {
    String contentId = "large-" + UUID.randomUUID();
    float[] vector = new float[768];
    vector[0] = 1;
    List<IndexedPassage> passages = new ArrayList<>();
    for (int i = 0; i < 4_500; i++) {
      String id = contentId + "-" + i;
      passages.add(
          new IndexedPassage(
              contentId,
              new Passage(
                  id,
                  i,
                  "passage " + i + " " + "x".repeat(1100),
                  new Provenance("src", null, "Title", contentId, id, Map.of()),
                  Map.of()),
              CHUNKER,
              vector,
              new EmbeddingModelDescriptor("stub", "stub-768", "test", 768)));
    }
    IndexClaim claim = store.claim(contentId, "fingerprint", CHUNKER).orElseThrow();
    long startedAt = System.nanoTime();

    store.replaceContent(claim, "fingerprint", passages);

    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
    System.out.println("replacement of 4500 passages took " + elapsed);
    assertThat(elapsed).as("the transaction limit is 60 s").isLessThan(Duration.ofSeconds(40));
    assertThat(
            jdbcClient
                .sql("SELECT passage_count FROM rag_content_index WHERE content_id = :id")
                .param("id", contentId)
                .query(Integer.class)
                .single())
        .isEqualTo(4_500);
    jdbcClient
        .sql("DELETE FROM rag_passage WHERE content_id = :id")
        .param("id", contentId)
        .update();
  }
}
