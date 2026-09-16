package org.signalengine.rag.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.indexing.IndexableContent;
import org.signalengine.rag.provenance.Provenance;

/** The deterministic structural baseline chunker. */
class StructureAwareChunkerTest {

  private final StructureAwareChunker chunker = new StructureAwareChunker();

  private static IndexableContent content(String text) {
    return new IndexableContent(
        "content-1",
        text,
        new Provenance("src-1", null, "T", "doc-1", null, Map.of("k", "v")),
        Map.of(
            IndexingMetadata.CONTENT_TYPE, "text/markdown",
            IndexingMetadata.LANGUAGE, "en",
            IndexingMetadata.PUBLISHED_AT, "2026-09-01T00:00:00Z"));
  }

  @Test
  void blankContentIsRejectedByTheContractItself() {
    assertThatThrownBy(() -> content("   \n\n  "))
        .isInstanceOf(IllegalArgumentException.class); // IndexableContent forbids blank text
  }

  @Test
  void producesOrderedNonEmptyPassages() {
    Chunking chunking = chunker.chunk(ChunkingTestCorpus.load(ChunkingTestCorpus.AI_TECHNOLOGY));

    assertThat(chunking.passages()).hasSizeGreaterThan(1);
    assertThat(chunking.passages()).allSatisfy(p -> assertThat(p.text()).isNotBlank());
    assertThat(chunking.passages())
        .extracting(Passage::ordinal)
        .containsExactlyElementsOf(indices(chunking.passageCount()));
  }

  @Test
  void aHeadingStartsANewPassage() {
    Chunking chunking =
        chunker.chunk(
            content(
                "Intro paragraph one.\n\nIntro paragraph two.\n\n"
                    + "## A section\n\nSection body paragraph."));

    assertThat(chunking.passages()).hasSize(2);
    assertThat(chunking.passages().get(0).text()).doesNotContain("## A section");
    assertThat(chunking.passages().get(1).text()).startsWith("## A section");
  }

  @Test
  void largeContentIsPackedUpToTheConfiguredSize() {
    String block = "word ".repeat(60).trim(); // ~300 chars
    String text = (block + "\n\n").repeat(10).trim(); // ~3000 chars, no headings
    Chunking chunking = new StructureAwareChunker(700).chunk(content(text));

    assertThat(chunking.passages()).hasSizeGreaterThan(1);
    assertThat(chunking.passages())
        .allSatisfy(p -> assertThat(p.text().length()).isLessThanOrEqualTo(700 + block.length()));
  }

  @Test
  void provenanceIsCarriedThroughWithThePassageIdSet() {
    Provenance sourceProvenance =
        new Provenance(
            "reuters",
            java.net.URI.create("https://example.test/x"),
            "Title",
            "doc-42",
            null,
            Map.of("author", "A"));
    IndexableContent input =
        new IndexableContent("content-42", "One.\n\n## Two\n\nBody.", sourceProvenance, Map.of());

    Chunking chunking = chunker.chunk(input);

    for (Passage passage : chunking.passages()) {
      Provenance p = passage.provenance();
      assertThat(p.sourceId()).isEqualTo("reuters");
      assertThat(p.originUri()).isEqualTo(java.net.URI.create("https://example.test/x"));
      assertThat(p.title()).isEqualTo("Title");
      assertThat(p.documentId()).isEqualTo("doc-42");
      assertThat(p.attributes()).containsEntry("author", "A");
      assertThat(p.passageId()).isEqualTo(passage.passageId());
    }
  }

  @Test
  void contentMetadataIsCarriedOntoEveryPassage() {
    Chunking chunking = chunker.chunk(content("First.\n\n## Next\n\nSecond."));

    assertThat(chunking.passages())
        .allSatisfy(
            p -> {
              assertThat(p.metadata())
                  .containsEntry(IndexingMetadata.CONTENT_TYPE, "text/markdown");
              assertThat(p.metadata()).containsEntry(IndexingMetadata.LANGUAGE, "en");
              assertThat(p.metadata())
                  .containsEntry(IndexingMetadata.PUBLISHED_AT, "2026-09-01T00:00:00Z");
              assertThat(p.metadata())
                  .containsEntry(IndexingMetadata.CHUNK_STRATEGY, "structure-aware");
              assertThat(p.metadata()).containsKey(IndexingMetadata.CHUNK_CHAR_START);
              assertThat(p.metadata()).containsKey(IndexingMetadata.CHUNK_CHAR_END);
            });
  }

  @Test
  void passageIdsAreDeterministicAcrossRuns() {
    IndexableContent input = ChunkingTestCorpus.load(ChunkingTestCorpus.REGULATION);

    List<String> first =
        new StructureAwareChunker()
            .chunk(input).passages().stream().map(Passage::passageId).toList();
    List<String> second =
        new StructureAwareChunker()
            .chunk(input).passages().stream().map(Passage::passageId).toList();

    assertThat(second).isEqualTo(first);
  }

  @Test
  void editingContentChangesTheAffectedPassageIds() {
    Chunking before = chunker.chunk(content("Alpha.\n\n## H\n\nBravo."));
    Chunking after = chunker.chunk(content("Alpha edited.\n\n## H\n\nBravo."));

    assertThat(after.passages().get(0).passageId())
        .isNotEqualTo(before.passages().get(0).passageId());
  }

  @Test
  void changingConfigurationChangesTheDescriptorAndTheIds() {
    IndexableContent input = ChunkingTestCorpus.load(ChunkingTestCorpus.CONSTRUCTION);

    Chunking wide = new StructureAwareChunker(2000).chunk(input);
    Chunking narrow = new StructureAwareChunker(400).chunk(input);

    assertThat(narrow.chunker().version()).isNotEqualTo(wide.chunker().version());
    assertThat(narrow.chunker().type()).isEqualTo(RagComponentType.CHUNKER);
    assertThat(narrow.passages().stream().map(Passage::passageId))
        .doesNotContainAnyElementsOf(wide.passages().stream().map(Passage::passageId).toList());
  }

  @Test
  void theResultIsLabelledStructureAware() {
    Chunking chunking = chunker.chunk(content("Body."));

    assertThat(chunking.chunker().implementationId()).isEqualTo("structure-aware-chunker");
    assertThat(chunking.metadata())
        .containsEntry(IndexingMetadata.CHUNK_STRATEGY, "structure-aware");
    assertThat(chunking.metadata()).containsKey(IndexingMetadata.PASSAGE_COUNT);
    assertThat(chunking.warnings()).isEmpty();
  }

  private static List<Integer> indices(int count) {
    return java.util.stream.IntStream.range(0, count).boxed().toList();
  }
}
