package org.signalengine.infrastructure.rag.chunking;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;
import org.signalengine.rag.chunking.IndexingMetadata;
import org.signalengine.rag.indexing.IndexableContent;
import org.signalengine.rag.provenance.Provenance;

/**
 * Maps a Signal Engine {@link RawInformationItem} (and its {@link Source}) into the generic {@link
 * IndexableContent} the RAG chunking layer consumes
 * (docs/adr/0010-indexing-foundation-and-semantic-chunking.md).
 *
 * <p>This is the one place Signal Engine business types meet the reusable RAG contracts, and it
 * lives in infrastructure so the RAG core stays business-free. It maps data only; it applies no
 * business rule and changes no ingestion behaviour.
 */
public final class SignalEngineIndexableContent {

  private SignalEngineIndexableContent() {}

  /**
   * @throws IllegalArgumentException if the item has not been normalized yet (no normalized content
   *     means there is nothing to chunk)
   */
  public static IndexableContent from(RawInformationItem item, Source source) {
    if (item == null || source == null) {
      throw new IllegalArgumentException("item and source must not be null");
    }
    String normalized = item.normalizedContent();
    if (normalized == null || normalized.isBlank()) {
      throw new IllegalArgumentException(
          "raw information item " + item.id() + " has no normalized content to index");
    }

    return new IndexableContent(
        item.id().toString(), normalized, provenance(item, source), metadata(item));
  }

  private static Provenance provenance(RawInformationItem item, Source source) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("sourceName", source.name());
    attributes.put("sourceType", source.type());
    return new Provenance(
        source.id().toString(),
        parseUri(item.originalUrl()),
        null, // a raw information item carries no title (docs/05-data-model.md Section 8)
        item.id().toString(),
        null,
        attributes);
  }

  private static Map<String, String> metadata(RawInformationItem item) {
    Map<String, String> metadata = new LinkedHashMap<>();
    // A format-aware normalizer (Q1) would set this precisely; for now the normalized
    // content is treated as plain text.
    metadata.put(IndexingMetadata.CONTENT_TYPE, "text/plain");
    if (item.language() != null && !item.language().isBlank()) {
      metadata.put(IndexingMetadata.LANGUAGE, item.language());
    }
    if (item.publishedAt() != null) {
      metadata.put(IndexingMetadata.PUBLISHED_AT, item.publishedAt().toString());
    }
    return metadata;
  }

  private static URI parseUri(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return new URI(value);
    } catch (URISyntaxException notAUri) {
      return null;
    }
  }
}
