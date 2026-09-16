package org.signalengine.infrastructure.ingestion.content;

/**
 * The shape a fetched HTTP response actually has, as decided by {@link ResponseFormatDetector} —
 * never by {@code Source.type}, which stays {@code "http"} regardless (docs/02-functional-spec.md
 * Section 17, Q1 remains open; this is a per-response runtime decision, not a new source type).
 */
public enum ResponseFormat {
  /** An RSS 2.0 feed (&lt;rss&gt;&lt;channel&gt;&lt;item&gt;...). */
  RSS,
  /** An Atom feed (&lt;feed&gt;&lt;entry&gt;...). */
  ATOM,
  /** An HTML page to run article extraction on. */
  HTML,
  /** Plain text — nothing to extract; passed through as collected (today's default behavior). */
  PLAIN_TEXT,
  /**
   * Neither a recognised feed, HTML, nor plain text — an explicit but unrecognised Content-Type, or
   * markup-like content that does not match a known shape. Never guessed at; the caller fails the
   * collection instead (docs/10-security.md Section 7 — "an unexpected ... encoding [is] treated as
   * a failure rather than guessed at silently", applied here to response shape as a whole).
   */
  UNKNOWN
}
