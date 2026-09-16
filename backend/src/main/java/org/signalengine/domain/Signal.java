package org.signalengine.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Information relevant and important enough to bring to the user's attention (docs/05-data-model.md
 * Section 10). Created from exactly one {@link RelevantInformation} record.
 *
 * <p>No signal kind, category, or score is modelled (Section 10). {@code state} is the only mutable
 * aspect. {@link #withState(SignalState)} returns a copy in a different state; it does not police
 * which transitions are allowed (that is open — Q15).
 */
public record Signal(
    UUID id, UUID relevantInformationId, SignalState state, Instant createdAt, Instant updatedAt) {

  public Signal withState(SignalState newState) {
    return new Signal(id, relevantInformationId, newState, createdAt, updatedAt);
  }
}
