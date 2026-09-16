package org.signalengine.rag.chunking;

/**
 * Well-known keys for the {@code metadata} maps on {@link
 * org.signalengine.rag.indexing.IndexableContent}, {@link Passage} and {@link Chunking}.
 *
 * <p>The metadata model is an <b>extensible but controlled</b> map: the stable, common facts have a
 * documented key here; anything genuinely variable uses its own key. This avoids both a rigid
 * record of speculative fields and an undocumented free-for-all.
 *
 * <p>The keys a value should survive on: {@code content} &rarr; {@code passage} (a chunker copies
 * content-level metadata onto every passage) &rarr; a future embedding, retrieval, context and
 * citation.
 */
public final class IndexingMetadata {

  private IndexingMetadata() {}

  /**
   * IANA media type of the source content, e.g. {@code "text/markdown"}. Content- and
   * passage-level.
   */
  public static final String CONTENT_TYPE = "contentType";

  /**
   * BCP-47 language tag of the content, when known, e.g. {@code "en"}. Content- and passage-level.
   */
  public static final String LANGUAGE = "language";

  /** ISO-8601 instant the source material was published, when known. Content- and passage-level. */
  public static final String PUBLISHED_AT = "publishedAt";

  /**
   * The chunking strategy that produced a passage, e.g. {@code "structure-aware"}, {@code
   * "semantic"}.
   */
  public static final String CHUNK_STRATEGY = "chunkStrategy";

  /** Number of source blocks/units that make up a passage. Passage-level. */
  public static final String CHUNK_UNIT_COUNT = "chunkUnitCount";

  /**
   * Inclusive start offset of the passage in the content's text, in UTF-16 code units.
   * Passage-level.
   */
  public static final String CHUNK_CHAR_START = "chunkCharStart";

  /**
   * Exclusive end offset of the passage in the content's text, in UTF-16 code units. Passage-level.
   */
  public static final String CHUNK_CHAR_END = "chunkCharEnd";

  /** Total character length of the content that was chunked. Run-level ({@link Chunking}). */
  public static final String SOURCE_CHAR_LENGTH = "sourceCharLength";

  /** Number of passages produced. Run-level ({@link Chunking}). */
  public static final String PASSAGE_COUNT = "passageCount";
}
