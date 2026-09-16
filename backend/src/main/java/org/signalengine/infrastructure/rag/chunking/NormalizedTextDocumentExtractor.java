package org.signalengine.infrastructure.rag.chunking;

import io.github.semanticchunker.document.DocumentSource;
import io.github.semanticchunker.document.DocumentUnit;
import io.github.semanticchunker.document.Heading;
import io.github.semanticchunker.document.ListItem;
import io.github.semanticchunker.document.Paragraph;
import io.github.semanticchunker.document.PreparedDocument;
import io.github.semanticchunker.document.Provenance;
import io.github.semanticchunker.extraction.DocumentExtractor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A {@code semantic-chunker} {@link DocumentExtractor} for content Signal Engine has already
 * normalized (docs/08-ingestion.md Section 7). It reads {@code text/plain} and {@code
 * text/markdown} and produces a flat, ordered {@link PreparedDocument} of {@link Heading}, {@link
 * ListItem} and {@link Paragraph} units &mdash; no Tika, no binary parsing.
 *
 * <p>Blocks are separated by blank lines. A line beginning {@code #}&hellip;{@code ######} is a
 * heading (its {@code #} markers are kept, being the literal source text); a block whose lines are
 * all list markers becomes one {@link ListItem} per line; anything else is a {@link Paragraph}.
 *
 * <p>The library's provenance invariant is honoured exactly: the offsets on each unit address the
 * concatenation of every unit's text in order with no separator, so {@code
 * normalizedText.substring(startOffset, endOffset)} is that unit's text.
 *
 * <p>Deterministic and stateless; safe for concurrent reuse.
 */
public final class NormalizedTextDocumentExtractor implements DocumentExtractor {

  /** Media types this extractor is registered for. */
  public static final String TEXT_PLAIN = "text/plain";

  public static final String TEXT_MARKDOWN = "text/markdown";

  private static final Set<String> SUPPORTED = Set.of(TEXT_PLAIN, TEXT_MARKDOWN);
  private static final Pattern BLANK_LINE = Pattern.compile("\\R[ \\t]*\\R");
  private static final Pattern LINE = Pattern.compile("\\R");
  private static final Pattern MARKDOWN_HEADING =
      Pattern.compile("^#{1,6}\\s+\\S.*", Pattern.DOTALL);
  private static final Pattern LIST_MARKER = Pattern.compile("^\\s*([-*+]|\\d+[.)])\\s+\\S.*");

  @Override
  public Set<String> supportedMediaTypes() {
    return SUPPORTED;
  }

  @Override
  public PreparedDocument extract(DocumentSource source) {
    String text = new String(source.content(), StandardCharsets.UTF_8);

    List<DocumentUnit> units = new ArrayList<>();
    int ordinal = 0;
    int offset = 0;
    for (String rawBlock : BLANK_LINE.split(text)) {
      String block = rawBlock.strip();
      if (block.isEmpty()) {
        continue;
      }
      if (MARKDOWN_HEADING.matcher(block).matches()) {
        units.add(new Heading(span(ordinal++, offset, block), block, Map.of()));
        offset += block.length();
      } else if (isList(block)) {
        for (String line : LINE.split(block)) {
          String item = line.strip();
          if (item.isEmpty()) {
            continue;
          }
          units.add(new ListItem(span(ordinal++, offset, item), item, Map.of()));
          offset += item.length();
        }
      } else {
        units.add(new Paragraph(span(ordinal++, offset, block), block, Map.of()));
        offset += block.length();
      }
    }

    return new PreparedDocument(units, Map.of());
  }

  private static Provenance span(int globalOrdinal, int start, String content) {
    return new Provenance(globalOrdinal, 0, start, start + content.length());
  }

  private static boolean isList(String block) {
    String[] lines = LINE.split(block);
    boolean any = false;
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      if (!LIST_MARKER.matcher(line.strip()).matches()) {
        return false;
      }
      any = true;
    }
    return any;
  }
}
