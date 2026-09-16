package org.signalengine.rag.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Chunker;
import org.signalengine.rag.chunking.Chunking;
import org.signalengine.rag.embedding.EmbeddingException;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.indexing.IndexingTestDoubles.DeterministicEmbeddingModel;
import org.signalengine.rag.indexing.IndexingTestDoubles.InMemoryIndexedPassageStore;
import org.signalengine.rag.indexing.IndexingTestDoubles.SplittingChunker;

/** The generic indexing pipeline: chunk → embed → store, deterministic, idempotent, explicit. */
class StagedIndexingPipelineTest {

  private final DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
  private final InMemoryIndexedPassageStore store = new InMemoryIndexedPassageStore();

  private StagedIndexingPipeline pipeline(Chunker chunker) {
    return StagedIndexingPipeline.builder()
        .chunker(chunker)
        .embeddingModel(embeddingModel)
        .store(store)
        .build();
  }

  private static IndexableContent threeBlocks() {
    return IndexingTestDoubles.content("c-1", "block one\n\nblock two\n\nblock three");
  }

  @Test
  void chunksEmbedsAndPersistsEveryPassage() {
    IndexingReport report = pipeline(new SplittingChunker("1")).index(threeBlocks());

    assertThat(report.passages()).isEqualTo(3);
    assertThat(report.embedded()).isEqualTo(3);
    assertThat(report.persisted()).isEqualTo(3);
    assertThat(report.failed()).isZero();
    assertThat(report.fullyIndexed()).isTrue();
    assertThat(store.entries).hasSize(3);
    assertThat(report.metadata())
        .containsEntry("inserted", "3")
        .containsEntry("embeddingModel", "fake-embed")
        .containsEntry("embeddingDimension", "4")
        .containsEntry("chunker", "splitting");
  }

  @Test
  void reRunningIsIdempotentAndReportsUpdates() {
    StagedIndexingPipeline pipeline = pipeline(new SplittingChunker("1"));
    pipeline.index(threeBlocks());
    IndexingReport second = pipeline.index(threeBlocks());

    assertThat(store.entries).hasSize(3); // not 6
    assertThat(second.persisted()).isEqualTo(3);
    assertThat(second.metadata()).containsEntry("inserted", "0").containsEntry("updated", "3");
  }

  @Test
  void aDifferentChunkerConfigurationCoexists() {
    pipeline(new SplittingChunker("1")).index(threeBlocks());
    pipeline(new SplittingChunker("2")).index(threeBlocks());

    // different chunker version -> different passage ids -> both sets of rows kept
    assertThat(store.entries).hasSize(6);
  }

  @Test
  void theIndexedPassageCarriesChunkerAndEmbeddingModelIdentity() {
    pipeline(new SplittingChunker("cfg-x")).index(threeBlocks());

    IndexedPassage any = store.entries.values().iterator().next();
    assertThat(any.contentId()).isEqualTo("c-1");
    assertThat(any.chunker().type()).isEqualTo(RagComponentType.CHUNKER);
    assertThat(any.chunker().version()).isEqualTo("cfg-x");
    assertThat(any.embeddingModel().provider()).isEqualTo("fake");
    assertThat(any.embeddingModel().dimension()).isEqualTo(4);
  }

  @Test
  void anEmbeddingFailureAbortsTheContentAndPersistsNothing() {
    embeddingModel.fail = true;

    assertThatThrownBy(() -> pipeline(new SplittingChunker("1")).index(threeBlocks()))
        .isInstanceOf(EmbeddingException.class);
    assertThat(store.entries).isEmpty();
  }

  @Test
  void aPerPassagePersistenceFailureIsCountedNotSwallowed() {
    IndexingReport firstRun = pipeline(new SplittingChunker("1")).index(threeBlocks());
    String victim = store.entries.keySet().iterator().next().split("\\|")[0];
    store.entries.clear();
    store.failForPassageId = victim;

    IndexingReport report = pipeline(new SplittingChunker("1")).index(threeBlocks());

    assertThat(firstRun.failed()).isZero();
    assertThat(report.failed()).isEqualTo(1);
    assertThat(report.persisted()).isEqualTo(2);
    assertThat(report.notes()).anyMatch(note -> note.contains("not stored"));
    assertThat(report.fullyIndexed()).isFalse();
  }

  @Test
  void aVectorCountMismatchIsAnIndexingException() {
    EmbeddingModel wrongCount =
        request ->
            new EmbeddingResult(
                List.of(new float[] {1, 2, 3, 4}), 4, IndexingTestDoubles.MODEL_768);

    assertThatThrownBy(
            () ->
                StagedIndexingPipeline.builder()
                    .chunker(new SplittingChunker("1"))
                    .embeddingModel(wrongCount)
                    .store(store)
                    .build()
                    .index(threeBlocks()))
        .isInstanceOf(IndexingException.class)
        .hasMessageContaining("1 vectors for 3 passages");
  }

  @Test
  void chunkingProducingNoPassagesIsReportedNotAnError() {
    Chunker empty =
        content ->
            new Chunking(
                content.contentId(),
                List.of(),
                new org.signalengine.rag.ComponentDescriptor(
                    RagComponentType.CHUNKER, "empty", "1"),
                List.of(),
                Map.of());

    IndexingReport report = pipeline(empty).index(threeBlocks());

    assertThat(report.passages()).isZero();
    assertThat(report.notes()).anyMatch(note -> note.contains("no passages"));
  }

  @Test
  void anEmptyEmbeddingRequestIsNeverSent() {
    // guards that forPassages is only called with the real passage texts
    EmbeddingModel spy =
        request -> {
          assertThat(request.role()).isEqualTo(org.signalengine.rag.embedding.TextRole.PASSAGE);
          assertThat(request.texts()).isNotEmpty();
          return new DeterministicEmbeddingModel().embed(request);
        };
    StagedIndexingPipeline.builder()
        .chunker(new SplittingChunker("1"))
        .embeddingModel(spy)
        .store(store)
        .build()
        .index(threeBlocks());
    assertThat(store.entries).hasSize(3);
  }

  @Test
  void embeddingRequestFactoryUsesPassageRole() {
    assertThat(EmbeddingRequest.forPassages(List.of("x")).role())
        .isEqualTo(org.signalengine.rag.embedding.TextRole.PASSAGE);
  }
}
