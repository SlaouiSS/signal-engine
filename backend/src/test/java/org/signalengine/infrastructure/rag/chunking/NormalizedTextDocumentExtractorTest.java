package org.signalengine.infrastructure.rag.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.semanticchunker.document.DocumentSource;
import io.github.semanticchunker.document.DocumentUnit;
import io.github.semanticchunker.document.Heading;
import io.github.semanticchunker.document.ListItem;
import io.github.semanticchunker.document.Paragraph;
import io.github.semanticchunker.document.PreparedDocument;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The plain-text / Markdown {@code DocumentExtractor}: structural units and correct offsets. */
class NormalizedTextDocumentExtractorTest {

  private final NormalizedTextDocumentExtractor extractor = new NormalizedTextDocumentExtractor();

  private PreparedDocument extract(String markdown) {
    return extractor.extract(
        DocumentSource.of(markdown.getBytes(StandardCharsets.UTF_8), "text/markdown"));
  }

  @Test
  void declaresPlainTextAndMarkdown() {
    assertThat(extractor.supportedMediaTypes())
        .containsExactlyInAnyOrder("text/plain", "text/markdown");
  }

  @Test
  void classifiesHeadingsParagraphsAndListItems() {
    PreparedDocument document =
        extract("# Title\n\nA paragraph of prose.\n\n- first point\n- second point");

    List<DocumentUnit> units = document.units();
    assertThat(units.get(0)).isInstanceOf(Heading.class);
    assertThat(units.get(1)).isInstanceOf(Paragraph.class);
    assertThat(units.get(2)).isInstanceOf(ListItem.class);
    assertThat(units.get(3)).isInstanceOf(ListItem.class);
    assertThat(units.get(3).text().orElseThrow()).contains("second point");
  }

  @Test
  void assignsSequentialGlobalOrdinalsInDocumentOrder() {
    PreparedDocument document = extract("# A\n\nbody a\n\n# B\n\nbody b");

    assertThat(document.units())
        .extracting(unit -> unit.provenance().globalOrdinal())
        .containsExactly(0, 1, 2, 3);
  }

  @Test
  void offsetsSatisfyTheLibraryProvenanceInvariant() {
    PreparedDocument document =
        extract(
            "# Heading one\n\nParagraph one is here.\n\n- bullet one\n- bullet two\n\nLast paragraph.");

    String normalizedText =
        document.units().stream().map(unit -> unit.text().orElse("")).reduce("", String::concat);

    for (DocumentUnit unit : document.units()) {
      var provenance = unit.provenance();
      assertThat(normalizedText.substring(provenance.startOffset(), provenance.endOffset()))
          .isEqualTo(unit.text().orElse(""));
    }
    // contiguous and non-overlapping
    int expectedStart = 0;
    for (DocumentUnit unit : document.units()) {
      assertThat(unit.provenance().startOffset()).isEqualTo(expectedStart);
      expectedStart = unit.provenance().endOffset();
    }
  }

  @Test
  void collapsesBlankRunsAndIgnoresLeadingTrailingWhitespace() {
    PreparedDocument document = extract("\n\n  First block.  \n\n\n\n  Second block.  \n\n");

    assertThat(document.units()).hasSize(2);
    assertThat(document.units().get(0).text().orElseThrow()).contains("First block.");
    assertThat(document.units().get(1).text().orElseThrow()).contains("Second block.");
  }
}
