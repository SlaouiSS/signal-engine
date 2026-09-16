package org.signalengine.application.ai;

import java.util.UUID;

/**
 * The result of an {@link AiCapabilityInvoker#invoke} call: either a validated capability result or
 * a typed {@link AiError}. A sealed outcome (rather than an exception) so a use case must handle
 * both, the way ingestion handles {@code CollectionOutcome}.
 *
 * @param <R> the capability's result type
 */
public sealed interface AiCapabilityOutcome<R> {

  /** The Python service returned a schema-valid result. */
  record Produced<R>(R result, AiResponseMetadata metadata, UUID correlationId)
      implements AiCapabilityOutcome<R> {}

  /** The call failed with a typed, categorised error. */
  record Failed<R>(AiError error) implements AiCapabilityOutcome<R> {}

  default boolean isProduced() {
    return this instanceof Produced<R>;
  }
}
