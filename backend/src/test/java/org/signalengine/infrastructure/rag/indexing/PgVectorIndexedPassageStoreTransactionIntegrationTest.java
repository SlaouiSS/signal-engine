package org.signalengine.infrastructure.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
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
import org.signalengine.rag.indexing.IndexingException;
import org.signalengine.rag.provenance.Provenance;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

/**
 * The replacement of a content's passages is atomic <em>through the Spring-managed store</em>,
 * against a real PostgreSQL (docs/adr/0018-pipeline-reliability.md Section 4).
 *
 * <p>The failure is injected <b>after</b> writes have really happened inside the transaction: the
 * replacement updates an existing passage, inserts a new one, deletes the stale ones, and only then
 * is rejected by the check that what is stored matches what was written (two passages share one id,
 * so one row would exist for two passages). Everything must be rolled back. A control run on the
 * <em>unproxied</em> store, which has no Spring transaction, shows the same failure leaves partial
 * writes behind — so this test can actually detect a missing or ineffective transaction.
 */
class PgVectorIndexedPassageStoreTransactionIntegrationTest
    extends AbstractPersistenceIntegrationTest {

  private static final ComponentDescriptor CHUNKER =
      new ComponentDescriptor(RagComponentType.CHUNKER, "structure-aware-chunker", "v1");
  private static final EmbeddingModelDescriptor GEMMA =
      new EmbeddingModelDescriptor("ollama", "embeddinggemma", "embed/v1", 768);

  /** The Spring bean: a transactional proxy around {@link PgVectorIndexedPassageStore}. */
  @Autowired private IndexedPassageStore store;

  @Autowired private JdbcClient jdbcClient;

  private final String contentId = "content-" + UUID.randomUUID();

  private IndexedPassage passage(String suffix, String text) {
    String id = contentId + "-" + suffix;
    Provenance provenance = new Provenance("src", null, "Title", "doc-" + contentId, id, Map.of());
    return new IndexedPassage(
        contentId,
        new Passage(id, 0, text, provenance, Map.of()),
        CHUNKER,
        vector(suffix.hashCode()),
        GEMMA);
  }

  private static float[] vector(int seed) {
    float[] vector = new float[768];
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) Math.sin((Math.abs(seed) % 1000 + 1) * 0.001 * (i + 1));
    }
    return vector;
  }

  /** A first, complete indexing of {@code a}, {@code b} and {@code c}. */
  private void indexOriginalPassages(IndexedPassageStore target) {
    IndexClaim claim = target.claim(contentId, "fingerprint-1", CHUNKER).orElseThrow();
    target.replaceContent(
        claim,
        "fingerprint-1",
        List.of(
            passage("a", "original a"), passage("b", "original b"), passage("c", "original c")));
  }

  /**
   * A replacement that writes before it fails: it rewrites {@code a}, adds {@code d}, deletes the
   * stale {@code b} and {@code c}, and is then rejected because {@code d} appears twice.
   */
  private void replaceWithAFailureAfterWrites(IndexedPassageStore target) {
    IndexClaim claim = target.claim(contentId, "fingerprint-2", CHUNKER).orElseThrow();
    assertThatThrownBy(
            () ->
                target.replaceContent(
                    claim,
                    "fingerprint-2",
                    List.of(
                        passage("a", "REWRITTEN a"),
                        passage("d", "new d"),
                        passage("d", "new d again"))))
        .isInstanceOf(IndexingException.class)
        .hasMessageContaining("nothing was committed");
  }

  private List<String> passageSnapshot() {
    return jdbcClient
        .sql(
            "SELECT id || '|' || passage_text || '|' || updated_at::text FROM rag_passage"
                + " WHERE content_id = :contentId ORDER BY id")
        .param("contentId", contentId)
        .query(String.class)
        .list();
  }

  private List<String> embeddingSnapshot() {
    return jdbcClient
        .sql(
            "SELECT e.passage_id || '|' || e.embedding_model || '|' || e.embedding::text || '|'"
                + " || e.updated_at::text FROM rag_passage_embedding e"
                + " JOIN rag_passage p ON p.id = e.passage_id WHERE p.content_id = :contentId"
                + " ORDER BY e.passage_id")
        .param("contentId", contentId)
        .query(String.class)
        .list();
  }

  /** The completion marker: the columns only a committed replacement may write. */
  private String completionMarkerSnapshot() {
    return jdbcClient
        .sql(
            "SELECT indexed_at::text || '|' || content_fingerprint || '|' || chunker_id || '|'"
                + " || chunker_version || '|' || embedding_model || '|' || passage_count"
                + " FROM rag_content_index WHERE content_id = :contentId")
        .param("contentId", contentId)
        .query(String.class)
        .single();
  }

  @Test
  void theSpringBeanIsATransactionalProxyAndReplaceContentIsTransactional() throws Exception {
    assertThat(AopUtils.isAopProxy(store)).as("the injected store is a Spring proxy").isTrue();

    Method replaceContent =
        PgVectorIndexedPassageStore.class.getMethod(
            "replaceContent", IndexClaim.class, String.class, List.class);
    assertThat(
            new AnnotationTransactionAttributeSource()
                .getTransactionAttribute(replaceContent, PgVectorIndexedPassageStore.class))
        .as("replaceContent carries a Spring transaction attribute")
        .isNotNull();
  }

  @Test
  void aFailureAfterRealWritesRollsBackThePassagesTheEmbeddingsAndTheMarker() {
    indexOriginalPassages(store);
    List<String> passagesBefore = passageSnapshot();
    List<String> embeddingsBefore = embeddingSnapshot();
    String markerBefore = completionMarkerSnapshot();
    assertThat(passagesBefore).hasSize(3);

    replaceWithAFailureAfterWrites(store);

    assertThat(passageSnapshot())
        .as("a was not rewritten, d was not added, b and c were not deleted")
        .isEqualTo(passagesBefore);
    assertThat(embeddingSnapshot()).as("embeddings strictly unchanged").isEqualTo(embeddingsBefore);
    assertThat(completionMarkerSnapshot())
        .as("the completion marker still describes the first, complete indexing")
        .isEqualTo(markerBefore);
  }

  @Test
  void withoutASpringTransactionTheSameFailureLeavesPartialWrites() {
    IndexedPassageStore unproxied = AopTestUtils.getTargetObject(store);
    indexOriginalPassages(unproxied);
    List<String> passagesBefore = passageSnapshot();

    replaceWithAFailureAfterWrites(unproxied);

    assertThat(passageSnapshot())
        .as("control: without a transaction the writes made before the failure stay")
        .isNotEqualTo(passagesBefore);
    assertThat(passageSnapshot()).anyMatch(row -> row.contains("REWRITTEN a"));
    assertThat(passageSnapshot()).noneMatch(row -> row.contains("original b"));
  }
}
