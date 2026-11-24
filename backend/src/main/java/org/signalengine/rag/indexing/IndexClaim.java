package org.signalengine.rag.indexing;

/**
 * The exclusive right to (re)index one content, taken before the expensive chunking and embedding
 * work and checked again when the result is stored (docs/adr/0018-pipeline-reliability.md).
 *
 * <p>{@code revision} is a fencing token: every claim of a content increases it, so a run whose
 * claim has expired and been taken over by a newer run holds an <em>older</em> revision, and its
 * result is rejected at store time ({@link StaleIndexClaimException}) instead of overwriting the
 * newer indexing.
 *
 * @param contentId the content being indexed; never blank
 * @param revision the fencing token of this claim; positive
 */
public record IndexClaim(String contentId, long revision) {

  public IndexClaim {
    if (contentId == null || contentId.isBlank()) {
      throw new IllegalArgumentException("contentId must not be blank");
    }
    if (revision < 1) {
      throw new IllegalArgumentException("revision must be positive: " + revision);
    }
  }
}
