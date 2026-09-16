package org.signalengine.infrastructure.rag.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.semanticchunker.chunker.SemanticChunker;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Chunking;
import org.signalengine.rag.chunking.ChunkingTestCorpus;
import org.signalengine.rag.chunking.IndexingMetadata;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.indexing.IndexableContent;
import org.signalengine.rag.provenance.Provenance;

/**
 * The {@link SemanticChunkerAdapter} drives the <b>real</b> {@code semantic-chunker-core} pipeline
 * &mdash; only the language model is a deterministic double ({@link HeadingBoundaryChunkingModel}).
 */
class SemanticChunkerAdapterTest {

  private HeadingBoundaryChunkingModel model;

  private SemanticChunkerAdapter adapter(String configVersion) {
    model = new HeadingBoundaryChunkingModel();
    SemanticChunker library =
        SemanticChunker.builder()
            .documentExtractor(new NormalizedTextDocumentExtractor())
            .chunkingModel(model)
            .build();
    return new SemanticChunkerAdapter(library, "text/markdown", configVersion);
  }

  @Test
  void chunksTheRealCorpusIntoOrderedNonEmptyPassages() {
    Chunking chunking =
        adapter("cfg-1").chunk(ChunkingTestCorpus.load(ChunkingTestCorpus.AI_TECHNOLOGY));

    assertThat(model.calls)
        .isGreaterThanOrEqualTo(1); // the real pipeline actually called the model
    assertThat(chunking.passages()).hasSizeGreaterThan(1);
    assertThat(chunking.passages()).allSatisfy(p -> assertThat(p.text()).isNotBlank());
    assertThat(chunking.passages())
        .extracting(Passage::ordinal)
        .isEqualTo(java.util.stream.IntStream.range(0, chunking.passageCount()).boxed().toList());
    assertThat(chunking.chunker().type()).isEqualTo(RagComponentType.CHUNKER);
    assertThat(chunking.chunker().implementationId()).isEqualTo("semantic-chunker-adapter");
  }

  @Test
  void sectionsBeginAtHeadings() {
    Chunking chunking =
        adapter("cfg-1")
            .chunk(
                indexable(
                    "Opening line.\n\n## First section\n\nAlpha.\n\n## Second section\n\nBravo."));

    assertThat(chunking.passages()).hasSize(3);
    assertThat(chunking.passages().get(0).text()).isEqualTo("Opening line.");
    assertThat(chunking.passages().get(1).text()).startsWith("## First section");
    assertThat(chunking.passages().get(2).text()).startsWith("## Second section");
  }

  @Test
  void provenanceIsCarriedThroughAndPassageIdSet() {
    Provenance source =
        new Provenance(
            "acme",
            java.net.URI.create("https://example.test/a"),
            "Title",
            "doc-9",
            null,
            java.util.Map.of());
    Chunking chunking =
        adapter("cfg-1")
            .chunk(
                new IndexableContent("c-9", "One.\n\n## Two\n\nBody.", source, java.util.Map.of()));

    for (Passage passage : chunking.passages()) {
      assertThat(passage.provenance().sourceId()).isEqualTo("acme");
      assertThat(passage.provenance().documentId()).isEqualTo("doc-9");
      assertThat(passage.provenance().passageId()).isEqualTo(passage.passageId());
    }
  }

  @Test
  void everyPassageIsLabelledSemanticAndCarriesContentMetadata() {
    Chunking chunking =
        adapter("cfg-1").chunk(ChunkingTestCorpus.load(ChunkingTestCorpus.REGULATION));

    assertThat(chunking.metadata()).containsEntry(IndexingMetadata.CHUNK_STRATEGY, "semantic");
    assertThat(chunking.metadata()).containsKey("windowsProcessed");
    assertThat(chunking.passages())
        .allSatisfy(
            p -> {
              assertThat(p.metadata()).containsEntry(IndexingMetadata.CHUNK_STRATEGY, "semantic");
              assertThat(p.metadata()).containsEntry(IndexingMetadata.LANGUAGE, "en");
              assertThat(p.metadata()).containsKey(IndexingMetadata.CHUNK_UNIT_COUNT);
              assertThat(p.metadata()).containsKey(IndexingMetadata.CHUNK_CHAR_START);
            });
  }

  @Test
  void passageIdsAreDeterministicAndConfigurationSensitive() {
    IndexableContent input = ChunkingTestCorpus.load(ChunkingTestCorpus.CONSTRUCTION);

    List<String> runOne = ids(adapter("cfg-1").chunk(input));
    List<String> runTwo = ids(adapter("cfg-1").chunk(input));
    List<String> otherConfig = ids(adapter("cfg-2").chunk(input));

    assertThat(runTwo).isEqualTo(runOne);
    assertThat(otherConfig).doesNotContainAnyElementsOf(runOne);
  }

  @Test
  void nullContentIsRejected() {
    assertThatThrownBy(() -> adapter("cfg-1").chunk(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void noSemanticChunkerLibraryTypeLeaksOntoTheGenericResult() {
    for (Method method : Chunking.class.getMethods()) {
      assertThat(method.getReturnType().getName()).doesNotStartWith("io.github.semanticchunker");
    }
    for (Method method : Passage.class.getMethods()) {
      assertThat(method.getReturnType().getName()).doesNotStartWith("io.github.semanticchunker");
    }
  }

  private static IndexableContent indexable(String text) {
    return new IndexableContent(
        "c-1",
        text,
        new Provenance("s", null, null, "d", null, java.util.Map.of()),
        java.util.Map.of());
  }

  private static List<String> ids(Chunking chunking) {
    return chunking.passages().stream().map(Passage::passageId).toList();
  }
}
