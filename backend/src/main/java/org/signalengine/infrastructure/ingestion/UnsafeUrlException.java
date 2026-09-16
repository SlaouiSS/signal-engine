package org.signalengine.infrastructure.ingestion;

/**
 * A URL a collector was about to fetch failed the {@link OutboundUrlValidator} checks (bad scheme,
 * unresolvable host, or an address in a disallowed range). Non-retryable.
 */
class UnsafeUrlException extends Exception {

  UnsafeUrlException(String message) {
    super(message);
  }
}
