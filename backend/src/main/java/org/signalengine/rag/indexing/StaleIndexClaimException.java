package org.signalengine.rag.indexing;

/**
 * The {@link IndexClaim} of a run is no longer the current one: a newer run claimed the same
 * content after this run's claim expired. Nothing was written. Not a failure of the content — the
 * newer run owns it — so the pipeline reports it as a skipped indexing, never as an error.
 */
public final class StaleIndexClaimException extends RuntimeException {

  public StaleIndexClaimException(IndexClaim claim) {
    super(
        "claim revision "
            + claim.revision()
            + " of content "
            + claim.contentId()
            + " was superseded by a newer indexing run");
  }
}
