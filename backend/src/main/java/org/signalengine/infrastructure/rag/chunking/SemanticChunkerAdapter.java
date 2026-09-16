package org.signalengine.infrastructure.rag.chunking;

import io.github.semanticchunker.chunker.ChunkingResult;
import io.github.semanticchunker.chunker.SemanticChunk;
import io.github.semanticchunker.chunker.Warning;
import io.github.semanticchunker.document.DocumentSource;
import io.github.semanticchunker.document.DocumentUnit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Chunker;
import org.signalengine.rag.chunking.Chunking;
import org.signalengine.rag.chunking.IndexingMetadata;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.chunking.PassageIds;
import org.signalengine.rag.indexing.IndexableContent;

/**
 * Implements the generic RAG {@link Chunker} on top of {@code
 * io.github.slaouiss:semantic-chunker-core}
 * (docs/adr/0010-indexing-foundation-and-semantic-chunking.md).
 *
 * <p>The library's types stay inside this class: generic {@link IndexableContent} in, generic
 * {@link Passage}s out. Each library {@link SemanticChunk} &mdash; a contiguous run of document
 * units &mdash; becomes one {@link Passage}, in order, with the content's {@link
 * org.signalengine.rag.provenance.Provenance} carried through and a deterministic id.
 *
 * <p>Every result is labelled {@code semantic} on its {@link Chunking#chunker() descriptor} and
 * {@link IndexingMetadata#CHUNK_STRATEGY} so it is never confused with the deterministic {@link
 * org.signalengine.rag.chunking.StructureAwareChunker} baseline.
 */
public final class SemanticChunkerAdapter implements Chunker {

  private static final String IMPLEMENTATION_ID = "semantic-chunker-adapter";
  private static final String PASSAGE_TEXT_JOIN = "\n\n";

  private final io.github.semanticchunker.chunker.SemanticChunker library;
  private final String defaultMediaType;
  private final ComponentDescriptor descriptor;

  /**
   * @param library the configured {@code semantic-chunker} facade (extractor + model)
   * @param defaultMediaType media type used when the content carries no {@link
   *     IndexingMetadata#CONTENT_TYPE}
   * @param configurationVersion a fingerprint of the semantic configuration (library version, the
   *     boundary capability, {@code maxInputTokens}&hellip;) &mdash; part of every passage id, so a
   *     configuration change is visible and comparable
   */
  public SemanticChunkerAdapter(
      io.github.semanticchunker.chunker.SemanticChunker library,
      String defaultMediaType,
      String configurationVersion) {
    if (library == null) {
      throw new IllegalArgumentException("library must not be null");
    }
    if (defaultMediaType == null || defaultMediaType.isBlank()) {
      throw new IllegalArgumentException("defaultMediaType must not be blank");
    }
    this.library = library;
    this.defaultMediaType = defaultMediaType;
    this.descriptor =
        new ComponentDescriptor(RagComponentType.CHUNKER, IMPLEMENTATION_ID, configurationVersion);
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

    String mediaType =
        content.metadata().getOrDefault(IndexingMetadata.CONTENT_TYPE, defaultMediaType);
    DocumentSource source =
        DocumentSource.of(content.text().getBytes(StandardCharsets.UTF_8), mediaType);

    ChunkingResult result = library.chunk(source);

    List<Passage> passages = new ArrayList<>();
    List<SemanticChunk> chunks = result.chunks();
    for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
      SemanticChunk chunk = chunks.get(ordinal);
      String text = joinUnitText(chunk.units());
      if (text.isBlank()) {
        continue;
      }
      String passageId =
          PassageIds.forPassage(
              content.contentId(), IMPLEMENTATION_ID, descriptor.version(), ordinal, text);
      passages.add(
          new Passage(
              passageId,
              ordinal,
              text,
              content.provenance().withPassageId(passageId),
              passageMetadata(content, chunk.units())));
    }

    return new Chunking(
        content.contentId(),
        passages,
        descriptor,
        warnings(result),
        runMetadata(content, result, mediaType));
  }

  private static String joinUnitText(List<DocumentUnit> units) {
    StringBuilder text = new StringBuilder();
    for (DocumentUnit unit : units) {
      String unitText = unit.text().orElse("").strip();
      if (unitText.isEmpty()) {
        continue;
      }
      if (text.length() > 0) {
        text.append(PASSAGE_TEXT_JOIN);
      }
      text.append(unitText);
    }
    return text.toString();
  }

  private static List<String> warnings(ChunkingResult result) {
    return result.warnings().stream().map(Warning::message).toList();
  }

  private static Map<String, String> passageMetadata(
      IndexableContent content, List<DocumentUnit> units) {
    Map<String, String> metadata = carriedMetadata(content);
    metadata.put(IndexingMetadata.CHUNK_STRATEGY, "semantic");
    metadata.put(IndexingMetadata.CHUNK_UNIT_COUNT, Integer.toString(units.size()));
    if (!units.isEmpty()) {
      metadata.put(
          IndexingMetadata.CHUNK_CHAR_START,
          Integer.toString(units.get(0).provenance().startOffset()));
      metadata.put(
          IndexingMetadata.CHUNK_CHAR_END,
          Integer.toString(units.get(units.size() - 1).provenance().endOffset()));
    }
    return metadata;
  }

  private Map<String, String> runMetadata(
      IndexableContent content, ChunkingResult result, String mediaType) {
    Map<String, String> metadata = carriedMetadata(content);
    metadata.put(IndexingMetadata.CHUNK_STRATEGY, "semantic");
    metadata.put(IndexingMetadata.CONTENT_TYPE, mediaType);
    metadata.put(IndexingMetadata.SOURCE_CHAR_LENGTH, Integer.toString(content.text().length()));
    metadata.put(IndexingMetadata.PASSAGE_COUNT, Integer.toString(result.chunks().size()));
    metadata.put("windowsProcessed", Integer.toString(result.processingInfo().windowsProcessed()));
    metadata.put("windowsDegraded", Integer.toString(result.processingInfo().windowsDegraded()));
    return metadata;
  }

  private static Map<String, String> carriedMetadata(IndexableContent content) {
    Map<String, String> metadata = new LinkedHashMap<>();
    content
        .metadata()
        .forEach(
            (key, value) -> {
              if (IndexingMetadata.CONTENT_TYPE.equals(key)
                  || IndexingMetadata.LANGUAGE.equals(key)
                  || IndexingMetadata.PUBLISHED_AT.equals(key)) {
                metadata.put(key, value);
              }
            });
    return metadata;
  }
}
