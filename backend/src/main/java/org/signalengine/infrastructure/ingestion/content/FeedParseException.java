package org.signalengine.infrastructure.ingestion.content;

/**
 * The response body could not be parsed as a well-formed RSS or Atom feed. A defined, recorded
 * outcome rather than an uncaught exception reaching the pipeline (docs/10-security.md Section 7 —
 * "a parser failure is a defined, recorded outcome, not a crash").
 */
public final class FeedParseException extends Exception {

  public FeedParseException(String message) {
    super(message);
  }

  public FeedParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
