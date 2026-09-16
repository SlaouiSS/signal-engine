package org.signalengine.infrastructure.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.infrastructure.rag.chunking.SignalEngineIndexableContent;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.indexing.IndexableContent;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.indexing.IndexingPipeline;
import org.signalengine.rag.indexing.IndexingReport;
import org.signalengine.rag.indexing.StagedIndexingPipeline;
import org.signalengine.rag.provenance.Provenance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The full generic pipeline &mdash; chunk, embed, persist &mdash; against real PostgreSQL/pgvector,
 * with a deterministic stub embedding model (the {@code embed} capability HTTP path is covered by
 * its own contract test and the Task 8.3A benchmark). Also exercises the Signal Engine content
 * bridge.
 */
class StagedIndexingPipelinePersistenceIntegrationTest extends AbstractPersistenceIntegrationTest {

  @Autowired private IndexedPassageStore store;
  @Autowired private org.signalengine.rag.chunking.StructureAwareChunker structureAwareChunker;
  @Autowired private JdbcClient jdbcClient;

  /** A stub that returns a deterministic 768-d unit-ish vector per text. Not a real model. */
  private static final EmbeddingModel STUB_768 =
      new EmbeddingModel() {
        @Override
        public EmbeddingResult embed(EmbeddingRequest request) {
          List<float[]> vectors = new ArrayList<>();
          for (String text : request.texts()) {
            float[] v = new float[768];
            int h = text.hashCode();
            for (int i = 0; i < v.length; i++) {
              v[i] = (float) Math.cos((h + i) * 0.01);
            }
            vectors.add(v);
          }
          return new EmbeddingResult(
              vectors, 768, new EmbeddingModelDescriptor("stub", "stub-768", "test", 768));
        }
      };

  private IndexingPipeline pipeline() {
    return StagedIndexingPipeline.builder()
        .chunker(structureAwareChunker)
        .embeddingModel(STUB_768)
        .store(store)
        .build();
  }

  @Test
  void indexesGenericContentIntoBothTables() {
    IndexableContent content =
        new IndexableContent(
            "content-e2e",
            "# Grid batteries\n\nGrid-scale batteries smoothed the evening peak last quarter.\n\n"
                + "## Permitting\n\nApprovals now take nine months on average.",
            new Provenance("acme-feed", null, null, "content-e2e", null, java.util.Map.of()),
            java.util.Map.of("language", "en"));

    IndexingReport report = pipeline().index(content);

    assertThat(report.passages()).isGreaterThan(1);
    assertThat(report.fullyIndexed()).isTrue();
    Integer stored =
        jdbcClient
            .sql(
                "SELECT count(*) FROM rag_passage_embedding e JOIN rag_passage p ON p.id = e.passage_id"
                    + " WHERE p.content_id = 'content-e2e' AND vector_dims(e.embedding) = 768")
            .query(Integer.class)
            .single();
    assertThat(stored).isEqualTo(report.persisted());
  }

  @Test
  void reIndexingTheSameContentDoesNotDuplicate() {
    IndexableContent content =
        new IndexableContent(
            "content-idem",
            "One paragraph.\n\nAnother paragraph here.",
            new Provenance("acme-feed", null, null, "content-idem", null, java.util.Map.of()),
            java.util.Map.of());

    IndexingReport first = pipeline().index(content);
    IndexingReport second = pipeline().index(content);

    Integer passages =
        jdbcClient
            .sql("SELECT count(*) FROM rag_passage WHERE content_id = 'content-idem'")
            .query(Integer.class)
            .single();
    assertThat(passages).isEqualTo(first.passages());
    assertThat(second.metadata()).containsEntry("inserted", "0");
  }

  @Test
  void theSignalEngineBridgeProducesIndexableContentThatFlowsThrough() {
    Source source =
        new Source(
            UUID.randomUUID(),
            "rss",
            "Example Feed",
            "https://example.test/feed",
            true,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    RawInformationItem item =
        new RawInformationItem(
            UUID.randomUUID(),
            source.id(),
            "src-99",
            "hash-99",
            "https://example.test/articles/99",
            "raw",
            "Normalized body one.\n\n## A heading\n\nNormalized body two, a little longer.",
            "en",
            Instant.parse("2026-08-30T00:00:00Z"),
            Instant.parse("2026-09-01T12:00:00Z"),
            ProcessingState.normalized(Instant.parse("2026-09-01T12:00:00Z")),
            null,
            Instant.parse("2026-09-01T12:00:00Z"),
            Instant.parse("2026-09-01T12:00:00Z"));

    IndexableContent content = SignalEngineIndexableContent.from(item, source);
    IndexingReport report = pipeline().index(content);

    assertThat(report.fullyIndexed()).isTrue();
    String storedSource =
        jdbcClient
            .sql("SELECT source_id FROM rag_passage WHERE content_id = :cid LIMIT 1")
            .param("cid", item.id().toString())
            .query(String.class)
            .single();
    assertThat(storedSource).isEqualTo(source.id().toString());
  }
}
