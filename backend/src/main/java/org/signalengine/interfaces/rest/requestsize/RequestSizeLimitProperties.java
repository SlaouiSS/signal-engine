package org.signalengine.interfaces.rest.requestsize;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Inbound HTTP request-body limit for the exposed {@code /api/v1} business API (docs/10-security.md
 * Section 10 — "enforce reasonable request bounds (e.g. bounded payload size)").
 *
 * <p>{@code maxRequestBytes} is a <strong>provisional default open to tuning</strong>
 * (docs/03-technical-spec.md Section 24, T9): every business request today is a small configuration
 * or feedback payload (a source URL, an interest description, a feedback verdict), so 256 KiB is
 * roughly three orders of magnitude of headroom while still bounding an oversized request before it
 * is buffered or parsed. It protects the exposed API only — the internal Java to Python capability
 * call has its own, larger limit ({@code AGENTS_MAX_REQUEST_BYTES}).
 */
@ConfigurationProperties("signal-engine.api")
public record RequestSizeLimitProperties(@DefaultValue("262144") long maxRequestBytes) {

  public RequestSizeLimitProperties {
    if (maxRequestBytes < 1) {
      throw new IllegalArgumentException("signal-engine.api.max-request-bytes must be positive");
    }
  }
}
