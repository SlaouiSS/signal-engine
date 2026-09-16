package org.signalengine.rag.provenance;

import java.net.URI;
import java.util.Map;

/**
 * Where a retrieved passage came from, expressed generically so a passage can always be traced back
 * to its origin and cited (docs/07-rag.md Section 11).
 *
 * <p>Independent of Signal Engine: {@code sourceId}, {@code documentId} and {@code passageId} are
 * opaque strings whose meaning is owned by whatever indexed the content. The core never interprets
 * them &mdash; it only carries them through retrieval, context assembly and citation.
 *
 * <p>Only {@code sourceId} is mandatory; {@code originUri}, {@code title}, {@code documentId} and
 * {@code passageId} are {@code null} when not known. Blank strings are normalised to {@code null}
 * by the canonical constructor. {@code attributes} is an open, immutable map for any further
 * citation metadata (author, published date, section&hellip;) a concrete deployment needs; no other
 * fields are invented here.
 *
 * @param sourceId identifier of the origin (feed, site, dataset, connector&hellip;); never blank
 * @param originUri the external/original URL of the source material, or {@code null}
 * @param title human-readable title of the document the passage belongs to, or {@code null}
 * @param documentId identifier of the document/record the passage was extracted from, or {@code
 *     null}
 * @param passageId identifier of the specific passage/chunk within that document, or {@code null}
 * @param attributes further citation metadata; keys never blank
 */
public record Provenance(
    String sourceId,
    URI originUri,
    String title,
    String documentId,
    String passageId,
    Map<String, String> attributes) {

  public Provenance {
    if (sourceId == null || sourceId.isBlank()) {
      throw new IllegalArgumentException("sourceId must not be blank");
    }
    title = blankToNull(title);
    documentId = blankToNull(documentId);
    passageId = blankToNull(passageId);
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    if (attributes.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
      throw new IllegalArgumentException("attribute keys must not be blank");
    }
  }

  /** A provenance that carries only the mandatory source identifier. */
  public static Provenance ofSource(String sourceId) {
    return new Provenance(sourceId, null, null, null, null, Map.of());
  }

  /** Returns a copy with {@code documentId} and {@code passageId} set. */
  public Provenance withLocation(String newDocumentId, String newPassageId) {
    return new Provenance(sourceId, originUri, title, newDocumentId, newPassageId, attributes);
  }

  /** Returns a copy with {@code passageId} set, keeping every other field (used by chunking). */
  public Provenance withPassageId(String newPassageId) {
    return new Provenance(sourceId, originUri, title, documentId, newPassageId, attributes);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
