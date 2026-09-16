package org.signalengine.rag.chunking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.signalengine.rag.indexing.IndexableContent;
import org.signalengine.rag.provenance.Provenance;

/**
 * A small, deterministic, offline corpus of realistic English articles &mdash; an AI/technology
 * piece, a regulatory piece, and a construction piece &mdash; each with headings, paragraphs and
 * lists. Loaded from {@code src/test/resources/rag/corpus/}; no network.
 */
public final class ChunkingTestCorpus {

  public static final String AI_TECHNOLOGY = "ai-technology.md";
  public static final String REGULATION = "regulation.md";
  public static final String CONSTRUCTION = "construction.md";

  private ChunkingTestCorpus() {}

  /** The article as a generic {@link IndexableContent} with full provenance and metadata. */
  public static IndexableContent load(String name) {
    return new IndexableContent(
        "corpus:" + name,
        read(name),
        new Provenance(
            "test-corpus",
            URI.create("https://example.test/articles/" + name),
            "Article " + name,
            "doc-" + name,
            null,
            Map.of("editor", "test")),
        Map.of(
            IndexingMetadata.CONTENT_TYPE, "text/markdown",
            IndexingMetadata.LANGUAGE, "en",
            IndexingMetadata.PUBLISHED_AT, "2026-09-01T00:00:00Z"));
  }

  public static String read(String name) {
    try (var in = ChunkingTestCorpus.class.getResourceAsStream("/rag/corpus/" + name)) {
      if (in == null) {
        throw new IllegalStateException("corpus resource not found: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
