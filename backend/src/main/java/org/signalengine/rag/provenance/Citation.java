package org.signalengine.rag.provenance;

/**
 * A reference from a generated answer back to the passage that supports it (docs/07-rag.md Section
 * 11). Carries the passage's {@link Provenance} and, when available, the exact span of passage text
 * the claim rests on.
 *
 * @param passageId identifier of the context passage this citation points to; never blank
 * @param provenance provenance of that passage, copied through unchanged from retrieval
 * @param quotedText the supporting span of passage text, or {@code null} when the generator does
 *     not provide one
 */
public record Citation(String passageId, Provenance provenance, String quotedText) {

  public Citation {
    if (passageId == null || passageId.isBlank()) {
      throw new IllegalArgumentException("passageId must not be blank");
    }
    if (provenance == null) {
      throw new IllegalArgumentException("provenance must not be null");
    }
    quotedText = quotedText == null || quotedText.isBlank() ? null : quotedText;
  }
}
