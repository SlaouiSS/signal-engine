package org.signalengine.application.ai;

import java.util.UUID;

/**
 * One call to an AI capability: which capability, which contract version, the capability-specific
 * payload, and a correlation id carried through to the Python service and back
 * (docs/03-technical-spec.md Section 8.2).
 *
 * <p>{@code payload} is any object the infrastructure adapter can serialise to JSON — typically a
 * small record defined by the calling use case. The application layer never sees the wire envelope.
 */
public record AiCapabilityRequest(
    String capability, int contractVersion, Object payload, UUID correlationId) {

  public AiCapabilityRequest {
    if (capability == null || capability.isBlank()) {
      throw new IllegalArgumentException("capability must be set");
    }
    if (payload == null) {
      throw new IllegalArgumentException("payload must be set");
    }
    if (correlationId == null) {
      correlationId = UUID.randomUUID();
    }
  }

  /** A request with a fresh correlation id. */
  public static AiCapabilityRequest of(String capability, int contractVersion, Object payload) {
    return new AiCapabilityRequest(capability, contractVersion, payload, UUID.randomUUID());
  }
}
