package org.signalengine.application.ingestion;

import java.util.UUID;

/**
 * What one call to {@link CollectFromSourceUseCase#collectFromSource(UUID)} did for a source — the
 * value a scheduler (or a future manual trigger) inspects (docs/02-functional-spec.md Section 6.2).
 *
 * <p>{@code failureReason} is set only for {@code NO_COLLECTOR} and {@code FAILED}.
 */
public record CollectionReport(
    UUID sourceId, Status status, int newItemCount, int duplicateCount, String failureReason) {

  public enum Status {
    /** The source was collected; some items may have been new, some already known. */
    COLLECTED,
    /** The source is disabled, so nothing was collected (docs/02-functional-spec.md R7). */
    SKIPPED_DISABLED,
    /** No collector is registered for the source's type (Q1). */
    NO_COLLECTOR,
    /** The collector reported a failure (source unreachable, unsafe URL, unusable content, ...). */
    FAILED
  }

  static CollectionReport collected(UUID sourceId, int newItemCount, int duplicateCount) {
    return new CollectionReport(sourceId, Status.COLLECTED, newItemCount, duplicateCount, null);
  }

  static CollectionReport skippedDisabled(UUID sourceId) {
    return new CollectionReport(sourceId, Status.SKIPPED_DISABLED, 0, 0, null);
  }

  static CollectionReport noCollector(UUID sourceId, String sourceType) {
    return new CollectionReport(
        sourceId, Status.NO_COLLECTOR, 0, 0, "no collector for source type '" + sourceType + "'");
  }

  static CollectionReport failed(UUID sourceId, String reason) {
    return new CollectionReport(sourceId, Status.FAILED, 0, 0, reason);
  }
}
