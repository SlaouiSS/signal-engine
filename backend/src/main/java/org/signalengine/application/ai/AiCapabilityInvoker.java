package org.signalengine.application.ai;

/**
 * Output port: invoke a versioned AI capability on the Python service and get back a validated
 * result or a typed error (docs/03-technical-spec.md Section 8; docs/06-ai-agents.md Section 3).
 *
 * <p>The implementation is responsible for the wire envelope, the HTTP call, response schema
 * validation, and mapping transport/timeout failures to a retryable {@link AiError}
 * (docs/03-technical-spec.md Section 8.3, 8.5). It never throws for an expected failure — it
 * returns {@link AiCapabilityOutcome.Failed}.
 *
 * <p>One generic port is enough for every capability (docs/06-ai-agents.md Section 9 — "no
 * additional provider abstraction"): capability-specific ports in later tasks delegate to this.
 */
public interface AiCapabilityInvoker {

  <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> resultType);
}
