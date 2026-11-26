package org.signalengine.rag.indexing;

/**
 * Why a claimed indexing is given up, which decides what it costs the content
 * (docs/adr/0019-pipeline-automation.md).
 *
 * @param cause what stopped the indexing
 * @param recoverable whether the same content may succeed later without any change to it
 * @param reason the diagnostic kept with the content
 */
public record AbandonedIndexing(Cause cause, boolean recoverable, String reason) {

  public enum Cause {
    /** Nothing about this content: a suspended capability, a shutdown. It costs nothing. */
    NOT_THE_CONTENT,
    /** The indexing was attempted and failed; it uses one of the content's attempts. */
    FAILED_ATTEMPT,
    /** An unexpected error, counted apart so a deterministic defect cannot repeat for ever. */
    UNEXPECTED_ERROR,
    /** The indexing did not fit in its time budget; repeating it for ever would be waste. */
    TIME_BUDGET_EXCEEDED,
    /** This content cannot be processed as it is: blocked at once, nothing truncated or lost. */
    CONTENT_REJECTED
  }
}
