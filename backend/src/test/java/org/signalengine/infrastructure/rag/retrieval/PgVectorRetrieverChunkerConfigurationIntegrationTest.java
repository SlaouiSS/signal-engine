package org.signalengine.infrastructure.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.indexing.IndexClaim;
import org.signalengine.rag.indexing.IndexedPassage;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievedPassage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Re-indexing a content with another chunker configuration must not surface duplicates in the
 * results (docs/adr/0018-pipeline-reliability.md, docs/adr/0012: passages of another configuration
 * coexist in storage). Retrieval returns the passages of the chunker configuration the completion
 * marker names; content without a marker (indexed before markers existed, or never completed) stays
 * searchable until it is reconciled.
 */
class PgVectorRetrieverChunkerConfigurationIntegrationTest
    extends AbstractPersistenceIntegrationTest {

  private static final int DIM = 768;
  private static final EmbeddingModelDescriptor GEMMA =
      new EmbeddingModelDescriptor("ollama", "embeddinggemma", "embed/v1", DIM);
  private static final ComponentDescriptor CHUNKER_V1 =
      new ComponentDescriptor(RagComponentType.CHUNKER, "structure-aware-chunker", "v1");
  private static final ComponentDescriptor CHUNKER_V2 =
      new ComponentDescriptor(RagComponentType.CHUNKER, "structure-aware-chunker", "v2");

  @Autowired private IndexedPassageStore store;
  @Autowired private JdbcClient jdbcClient;

  private final String contentId = "content-" + UUID.randomUUID();

  private static float[] axis(int index) {
    float[] vector = new float[DIM];
    vector[index] = 1f;
    return vector;
  }

  private IndexedPassage passage(String id, ComponentDescriptor chunker, float[] vector) {
    Provenance provenance = new Provenance("src", null, "Title", "doc-" + contentId, id, Map.of());
    return new IndexedPassage(
        contentId, new Passage(id, 0, "text " + id, provenance, Map.of()), chunker, vector, GEMMA);
  }

  private void index(ComponentDescriptor chunker, String fingerprint, IndexedPassage... passages) {
    IndexClaim claim = store.claim(contentId, fingerprint, chunker).orElseThrow();
    store.replaceContent(claim, fingerprint, List.of(passages));
  }

  private List<String> retrievedPassageIds() {
    EmbeddingModel queryEmbedding = request -> new EmbeddingResult(List.of(axis(0)), DIM, GEMMA);
    return new PgVectorRetriever(queryEmbedding, jdbcClient)
        .retrieve(Query.of("q", 10).withMetadataFilter("contentId", contentId)).passages().stream()
            .map(RetrievedPassage::passageId)
            .toList();
  }

  @Test
  void reIndexingWithAnotherChunkerConfigurationDoesNotSurfaceTheOldPassagesAsDuplicates() {
    index(
        CHUNKER_V1,
        "fp",
        passage(contentId + "-v1-a", CHUNKER_V1, axis(0)),
        passage(contentId + "-v1-b", CHUNKER_V1, axis(1)));
    assertThat(retrievedPassageIds()).hasSize(2);

    index(
        CHUNKER_V2,
        "fp",
        passage(contentId + "-v2-a", CHUNKER_V2, axis(0)),
        passage(contentId + "-v2-b", CHUNKER_V2, axis(1)));

    assertThat(retrievedPassageIds())
        .as("only the configuration the completion marker names is retrieved")
        .containsExactlyInAnyOrder(contentId + "-v2-a", contentId + "-v2-b");
    Integer storedPassages =
        jdbcClient
            .sql("SELECT count(*) FROM rag_passage WHERE content_id = :contentId")
            .param("contentId", contentId)
            .query(Integer.class)
            .single();
    assertThat(storedPassages).as("ADR 0012: nothing was deleted").isEqualTo(4);
  }

  @Test
  void aContentIndexedBeforeCompletionMarkersExistedRemainsSearchableUntilReconciled() {
    index(CHUNKER_V1, "fp", passage(contentId + "-legacy", CHUNKER_V1, axis(0)));
    jdbcClient
        .sql("DELETE FROM rag_content_index WHERE content_id = :contentId")
        .param("contentId", contentId)
        .update();

    assertThat(retrievedPassageIds()).containsExactly(contentId + "-legacy");
  }

  @Test
  void aReIndexingInProgressDoesNotHideTheContentThatIsAlreadyIndexed() {
    index(CHUNKER_V1, "fp", passage(contentId + "-v1-a", CHUNKER_V1, axis(0)));

    store.claim(contentId, "fp-new", CHUNKER_V2).orElseThrow(); // a run that has not finished

    assertThat(retrievedPassageIds()).containsExactly(contentId + "-v1-a");
  }
}
