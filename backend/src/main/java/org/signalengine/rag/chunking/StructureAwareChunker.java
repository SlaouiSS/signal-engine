package org.signalengine.rag.chunking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.indexing.IndexableContent;

/**
 * A deterministic, dependency-free {@link Chunker}: the structural baseline.
 *
 * <p>It is <b>not</b> a semantic chunker and does not pretend to be one &mdash; every {@link
 * Chunking} it produces is labelled {@code structure-aware} on its {@link Chunking#chunker()
 * descriptor} and {@link IndexingMetadata#CHUNK_STRATEGY} metadata. It exists as a working default
 * and as a fixed baseline the semantic chunker can later be measured against.
 *
 * <p>Algorithm (deliberately simple):
 *
 * <ol>
 *   <li>Split the content text on blank lines into blocks; trim each; drop empties.
 *   <li>Walk the blocks, packing them into passages up to {@code maxCharsPerPassage}. A Markdown
 *       heading block (a line beginning {@code #}&hellip;{@code ######}) starts a new passage so it
 *       leads its section. A single block longer than the limit becomes its own passage &mdash; the
 *       baseline never splits inside a block.
 *   <li>A passage's text is its blocks joined by a blank line. Character offsets are recorded
 *       against the normalized block-joined text, not the raw input.
 * </ol>
 *
 * <p>Fully deterministic: the same content and the same {@code maxCharsPerPassage} always yield the
 * same passages with the same ids.
 */
public final class StructureAwareChunker implements Chunker {

  /** Provisional default passage size; not a tuned value (docs/07-rag.md Section 21). */
  public static final int DEFAULT_MAX_CHARS_PER_PASSAGE = 1200;

  private static final String IMPLEMENTATION_ID = "structure-aware-chunker";
  private static final Pattern BLANK_LINE = Pattern.compile("\\R[ \\t]*\\R");
  private static final Pattern MARKDOWN_HEADING =
      Pattern.compile("^#{1,6}\\s+\\S.*", Pattern.DOTALL);
  private static final String BLOCK_JOIN = "\n\n";

  private final int maxCharsPerPassage;
  private final ComponentDescriptor descriptor;

  public StructureAwareChunker() {
    this(DEFAULT_MAX_CHARS_PER_PASSAGE);
  }

  public StructureAwareChunker(int maxCharsPerPassage) {
    if (maxCharsPerPassage < 1) {
      throw new IllegalArgumentException(
          "maxCharsPerPassage must be positive: " + maxCharsPerPassage);
    }
    this.maxCharsPerPassage = maxCharsPerPassage;
    this.descriptor =
        new ComponentDescriptor(
            RagComponentType.CHUNKER,
            IMPLEMENTATION_ID,
            "1;maxCharsPerPassage=" + maxCharsPerPassage);
  }

  @Override
  public ComponentDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public Chunking chunk(IndexableContent content) {
    if (content == null) {
      throw new IllegalArgumentException("content must not be null");
    }

    List<String> blocks = splitIntoBlocks(content.text());
    List<String> passageTexts = packIntoPassages(blocks);
    String normalizedText = String.join(BLOCK_JOIN, passageTexts);

    List<Passage> passages = new ArrayList<>();
    int cursor = 0;
    for (int ordinal = 0; ordinal < passageTexts.size(); ordinal++) {
      String passageText = passageTexts.get(ordinal);
      int start = normalizedText.indexOf(passageText, cursor);
      int end = start + passageText.length();
      cursor = end;

      String passageId =
          PassageIds.forPassage(
              content.contentId(),
              descriptor.implementationId(),
              descriptor.version(),
              ordinal,
              passageText);

      passages.add(
          new Passage(
              passageId,
              ordinal,
              passageText,
              content.provenance().withPassageId(passageId),
              passageMetadata(content, passageText, start, end)));
    }

    return new Chunking(
        content.contentId(),
        passages,
        descriptor,
        List.of(),
        runMetadata(content, normalizedText.length(), passages.size()));
  }

  private List<String> packIntoPassages(List<String> blocks) {
    List<String> passages = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String block : blocks) {
      boolean heading = MARKDOWN_HEADING.matcher(block).matches();
      boolean wouldOverflow =
          current.length() > 0
              && current.length() + BLOCK_JOIN.length() + block.length() > maxCharsPerPassage;
      if (current.length() > 0 && (heading || wouldOverflow)) {
        passages.add(current.toString());
        current.setLength(0);
      }
      if (current.length() > 0) {
        current.append(BLOCK_JOIN);
      }
      current.append(block);
    }
    if (current.length() > 0) {
      passages.add(current.toString());
    }
    return passages;
  }

  private static List<String> splitIntoBlocks(String text) {
    List<String> blocks = new ArrayList<>();
    for (String candidate : BLANK_LINE.split(text)) {
      String block = candidate.strip();
      if (!block.isEmpty()) {
        blocks.add(block);
      }
    }
    return blocks;
  }

  private static Map<String, String> passageMetadata(
      IndexableContent content, String passageText, int start, int end) {
    Map<String, String> metadata = carriedMetadata(content);
    metadata.put(IndexingMetadata.CHUNK_STRATEGY, "structure-aware");
    metadata.put(IndexingMetadata.CHUNK_UNIT_COUNT, Integer.toString(countBlocks(passageText)));
    metadata.put(IndexingMetadata.CHUNK_CHAR_START, Integer.toString(start));
    metadata.put(IndexingMetadata.CHUNK_CHAR_END, Integer.toString(end));
    return metadata;
  }

  private static Map<String, String> runMetadata(
      IndexableContent content, int normalizedLength, int passageCount) {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put(IndexingMetadata.CHUNK_STRATEGY, "structure-aware");
    metadata.put(IndexingMetadata.SOURCE_CHAR_LENGTH, Integer.toString(normalizedLength));
    metadata.put(IndexingMetadata.PASSAGE_COUNT, Integer.toString(passageCount));
    content
        .metadata()
        .forEach(
            (key, value) -> {
              if (isCarried(key)) {
                metadata.put(key, value);
              }
            });
    return metadata;
  }

  private static Map<String, String> carriedMetadata(IndexableContent content) {
    Map<String, String> metadata = new LinkedHashMap<>();
    content
        .metadata()
        .forEach(
            (key, value) -> {
              if (isCarried(key)) {
                metadata.put(key, value);
              }
            });
    return metadata;
  }

  private static boolean isCarried(String key) {
    return IndexingMetadata.CONTENT_TYPE.equals(key)
        || IndexingMetadata.LANGUAGE.equals(key)
        || IndexingMetadata.PUBLISHED_AT.equals(key);
  }

  private static int countBlocks(String passageText) {
    return BLANK_LINE.split(passageText).length;
  }
}
