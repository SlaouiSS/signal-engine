package org.signalengine.application.ai;

import java.util.Optional;

/**
 * Carries the typed {@link AiError} of a failed capability call through code that can only throw —
 * the RAG embedding and chunking models report failure as exceptions of their own types. It is used
 * as the <em>cause</em> of those exceptions so the application layer can still tell a transient
 * failure from a permanent one, or a suspended capability, without parsing messages.
 */
public final class AiCapabilityFailureException extends RuntimeException {

  private final transient AiError error;

  public AiCapabilityFailureException(AiError error) {
    super(error.message());
    this.error = error;
  }

  public AiError error() {
    return error;
  }

  /** The capability failure behind {@code failure}, if one is anywhere in its cause chain. */
  public static Optional<AiError> findIn(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof AiCapabilityFailureException capabilityFailure) {
        return Optional.of(capabilityFailure.error());
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return Optional.empty();
  }
}
