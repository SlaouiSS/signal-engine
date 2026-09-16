package org.signalengine.application.ingestion;

import java.time.Instant;
import org.signalengine.domain.Source;

/**
 * One unit of information a {@link SourceCollector} obtained from a source, before Java normalises
 * and persists it as a {@link org.signalengine.domain.RawInformationItem} (docs/08-ingestion.md
 * Section 5; docs/05-data-model.md Section 8).
 *
 * <p>All fields except {@code rawContent} may be {@code null} — a source may not expose an item id,
 * a canonical URL, a title, a publication time, or a media type. {@code rawContent} is the exact
 * content as collected and is treated as untrusted data throughout the pipeline
 * (docs/08-ingestion.md Section 21); it is never executed or interpreted as instructions.
 *
 * <p>{@code "Item"} is the data-model term for a unit of collected information (Raw Information
 * Item, docs/05-data-model.md Section 8), not a vague name.
 */
public record CollectedItem(
    String sourceProvidedId,
    String originalUrl,
    String title,
    Instant publishedAt,
    String rawContent,
    String mediaType) {

  /** A collected item that carries only its content and the URL it came from. */
  public static CollectedItem of(Source source, String rawContent, String mediaType) {
    return new CollectedItem(null, source.reference(), null, null, rawContent, mediaType);
  }
}
