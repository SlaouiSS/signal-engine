package org.signalengine.infrastructure.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.indexing.IndexedPassage;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.signalengine.rag.retrieval.RetrievedPassage;
import org.signalengine.rag.retrieval.Retriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Semantic retrieval against real PostgreSQL + pgvector, with <b>crafted</b> 768-d vectors whose
 * cosine ordering is known exactly, so correctness — ordering, distance→score, top-K,
 * provenance/metadata, model isolation — is verified precisely. The real-embedding quality baseline
 * is the separate {@link PgVectorRetrievalBenchmark}.
 */
class PgVectorRetrieverIntegrationTest extends AbstractPersistenceIntegrationTest {

  private static final int DIM = 768;
  private static final EmbeddingModelDescriptor GEMMA =
      new EmbeddingModelDescriptor("ollama", "embeddinggemma", "embed/v1", DIM);
  private static final ComponentDescriptor CHUNKER =
      new ComponentDescriptor(RagComponentType.CHUNKER, "structure-aware-chunker", "1");

  @Autowired private IndexedPassageStore store;
  @Autowired private JdbcClient jdbcClient;

  /** A unit vector along one axis. */
  private static float[] axis(int index, float value) {
    float[] v = new float[DIM];
    v[index] = value;
    return v;
  }

  /** value1 along axis1, value2 along axis2 (used to make a known cosine angle). */
  private static float[] plane(int a1, float v1, int a2, float v2) {
    float[] v = new float[DIM];
    v[a1] = v1;
    v[a2] = v2;
    return v;
  }

  private Retriever retrieverEmbeddingQueryAs(float[] queryVector, EmbeddingModelDescriptor model) {
    EmbeddingModel stub =
        request -> new EmbeddingResult(List.of(queryVector.clone()), queryVector.length, model);
    return new PgVectorRetriever(stub, jdbcClient);
  }

  private void index(
      String id,
      String contentId,
      String sourceId,
      float[] vector,
      EmbeddingModelDescriptor model) {
    Provenance provenance =
        new Provenance(
            sourceId,
            java.net.URI.create("https://example.test/a/" + id),
            "Article " + id,
            "doc-" + contentId,
            id,
            Map.of("author", "A. Writer"));
    Passage passage =
        new Passage(id, 0, "passage body for " + id, provenance, Map.of("language", "en"));
    store.save(new IndexedPassage(contentId, passage, CHUNKER, vector, model));
  }

  @Test
  void returnsPassagesOrderedByCosineSimilarityWithAnExplicitScore() {
    String c = "content-order";
    index(c + "-A", c, "s1", axis(0, 1f), GEMMA); // cos 1.0, distance 0
    index(c + "-B", c, "s1", plane(0, 0.5f, 1, 0.8660254f), GEMMA); // cos 0.5, distance 0.5
    index(c + "-C", c, "s1", axis(1, 1f), GEMMA); // cos 0.0, distance 1.0
    index(c + "-D", c, "s1", axis(0, -1f), GEMMA); // cos -1.0, distance 2.0

    RetrievalResult result =
        retrieverEmbeddingQueryAs(axis(0, 1f), GEMMA)
            .retrieve(Query.of("anything").withMetadataFilter("contentId", c));

    assertThat(result.passages())
        .extracting(RetrievedPassage::passageId)
        .containsExactly(c + "-A", c + "-B", c + "-C", c + "-D");
    assertThat(result.passages().get(0).score()).isCloseTo(1.0, within(1e-4));
    assertThat(result.passages().get(1).score()).isCloseTo(0.5, within(1e-4));
    assertThat(result.passages().get(2).score()).isCloseTo(0.0, within(1e-4));
    assertThat(result.passages().get(3).score()).isCloseTo(-1.0, within(1e-4));
    assertThat(result.passages())
        .allSatisfy(p -> assertThat(p.metadata()).containsKey("cosineDistance"));
    assertThat(result.metadata())
        .containsEntry("strategy", "pgvector-cosine-exact")
        .containsEntry("embeddingModel", "embeddinggemma")
        .containsEntry("scoreConvention", "1 - cosine_distance (higher is more similar)");
  }

  @Test
  void topKIsRespectedAndValidated() {
    String c = "content-topk";
    for (int i = 0; i < 5; i++) {
      index(c + "-" + i, c, "s1", plane(0, 1f, i + 2, 0.1f * (i + 1)), GEMMA);
    }
    RetrievalResult two =
        retrieverEmbeddingQueryAs(axis(0, 1f), GEMMA)
            .retrieve(Query.of("q", 2).withMetadataFilter("contentId", c));
    assertThat(two.passages()).hasSize(2);
    assertThat(two.metadata()).containsEntry("topK", "2").containsEntry("returned", "2");

    assertThatThrownBy(() -> Query.of("q", 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Query.of("q", Query.MAX_TOP_K + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void provenanceAndMetadataAreCarriedThrough() {
    String c = "content-prov";
    index(c + "-P", c, "src-x", axis(3, 1f), GEMMA);

    RetrievedPassage p =
        retrieverEmbeddingQueryAs(axis(3, 1f), GEMMA)
            .retrieve(Query.of("q").withMetadataFilter("contentId", c))
            .passages()
            .get(0);

    assertThat(p.provenance().sourceId()).isEqualTo("src-x");
    assertThat(p.provenance().documentId()).isEqualTo("doc-" + c);
    assertThat(p.provenance().title()).isEqualTo("Article " + c + "-P");
    assertThat(p.provenance().originUri())
        .isEqualTo(java.net.URI.create("https://example.test/a/" + c + "-P"));
    assertThat(p.provenance().passageId()).isEqualTo(c + "-P");
    assertThat(p.provenance().attributes()).containsEntry("author", "A. Writer");
    assertThat(p.metadata()).containsEntry("language", "en").containsEntry("contentId", c);
    assertThat(p.text()).isEqualTo("passage body for " + c + "-P");
  }

  @Test
  void embeddingsFromADifferentModelAreNeverMixedIn() {
    String c = "content-model-iso";
    EmbeddingModelDescriptor otherModel =
        new EmbeddingModelDescriptor("ollama", "granite-embedding", "embed/v1", DIM);
    // the closest vector belongs to a DIFFERENT model
    index(c + "-OTHER", c, "s1", axis(0, 1f), otherModel);
    index(c + "-GEMMA", c, "s1", plane(0, 0.2f, 5, 1f), GEMMA);

    RetrievalResult result =
        retrieverEmbeddingQueryAs(axis(0, 1f), GEMMA)
            .retrieve(Query.of("q").withMetadataFilter("contentId", c));

    assertThat(result.passages())
        .extracting(RetrievedPassage::passageId)
        .containsExactly(c + "-GEMMA");
  }

  @Test
  void metadataFiltersScopeBySourceAndRecordIgnoredKeys() {
    String c = "content-filter";
    index(c + "-s1", c, "source-1", axis(0, 1f), GEMMA);
    index(c + "-s2", c, "source-2", axis(0, 1f), GEMMA);

    RetrievalResult scoped =
        retrieverEmbeddingQueryAs(axis(0, 1f), GEMMA)
            .retrieve(
                Query.of("q")
                    .withMetadataFilter("contentId", c)
                    .withMetadataFilter("sourceId", "source-1")
                    .withMetadataFilter("area", "AI & Technology"));

    assertThat(scoped.passages())
        .extracting(RetrievedPassage::passageId)
        .containsExactly(c + "-s1");
    assertThat(scoped.metadata()).containsEntry("appliedFilters", "contentId,sourceId");
    assertThat(scoped.metadata()).containsEntry("ignoredFilters", "area");
  }

  @Test
  void anEmptyCorpusForThisModelReturnsAnEmptyResult() {
    RetrievalResult result =
        retrieverEmbeddingQueryAs(axis(0, 1f), GEMMA)
            .retrieve(
                Query.of("q").withMetadataFilter("contentId", "content-that-was-never-indexed"));

    assertThat(result.isEmpty()).isTrue();
    assertThat(result.metadata()).containsEntry("returned", "0");
  }

  @Test
  void repeatedRetrievalIsDeterministic() {
    String c = "content-determinism";
    for (int i = 0; i < 6; i++) {
      index(c + "-" + i, c, "s1", plane(0, 1f, i + 2, 0.05f * (i + 1)), GEMMA);
    }
    Retriever retriever = retrieverEmbeddingQueryAs(axis(0, 1f), GEMMA);
    Query query = Query.of("q", 6).withMetadataFilter("contentId", c);

    List<String> first =
        retriever.retrieve(query).passages().stream().map(RetrievedPassage::passageId).toList();
    List<String> second =
        retriever.retrieve(query).passages().stream().map(RetrievedPassage::passageId).toList();
    assertThat(second).isEqualTo(first);
  }

  @Test
  void noApproximateVectorIndexIsRequiredForRetrievalToWork() {
    Integer annIndexes =
        jdbcClient
            .sql(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'rag_passage_embedding'"
                    + " AND (indexdef ILIKE '%hnsw%' OR indexdef ILIKE '%ivfflat%')")
            .query(Integer.class)
            .single();
    assertThat(annIndexes).isZero();

    String c = "content-no-ann";
    index(c + "-A", c, "s1", axis(0, 1f), GEMMA);
    RetrievalResult result =
        retrieverEmbeddingQueryAs(axis(0, 1f), GEMMA)
            .retrieve(Query.of("q").withMetadataFilter("contentId", c));
    assertThat(result.passages()).hasSize(1); // exact scan, no ANN index
  }

  @Test
  void theGenericRetrieverContractExposesNoPostgresOrPgvectorTypes() {
    for (Class<?> type : List.of(RetrievalResult.class, RetrievedPassage.class, Retriever.class)) {
      for (Method method : type.getMethods()) {
        String returnType = method.getReturnType().getName();
        assertThat(returnType)
            .doesNotStartWith("org.postgresql")
            .doesNotStartWith("com.pgvector")
            .doesNotStartWith("org.springframework.jdbc");
      }
    }
  }
}
