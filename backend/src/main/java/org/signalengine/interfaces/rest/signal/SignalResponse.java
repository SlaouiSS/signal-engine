package org.signalengine.interfaces.rest.signal;

import java.time.Instant;
import java.util.UUID;
import org.signalengine.domain.Signal;
import org.signalengine.domain.SignalState;

/** API representation of a signal (docs/05-data-model.md Section 10). */
public record SignalResponse(
    UUID id, UUID relevantInformationId, SignalState state, Instant createdAt, Instant updatedAt) {

  public static SignalResponse from(Signal signal) {
    return new SignalResponse(
        signal.id(),
        signal.relevantInformationId(),
        signal.state(),
        signal.createdAt(),
        signal.updatedAt());
  }
}
