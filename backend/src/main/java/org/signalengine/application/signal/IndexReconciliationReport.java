package org.signalengine.application.signal;

import java.util.UUID;

/**
 * What reconciling the knowledge base index did for one Raw Information Item — the value a
 * scheduler (or the startup run) inspects.
 *
 * <p>{@code detail} carries the failure or skip reason where relevant.
 */
public record IndexReconciliationReport(UUID rawInformationItemId, Outcome outcome, String detail) {

  public enum Outcome {
    /** The item is now fully indexed, with a completion marker. */
    INDEXED,
    /** Nothing was done: another run is indexing it, or its source no longer exists. */
    SKIPPED,
    /** Indexing failed or was incomplete; it is attempted again later, up to a bounded count. */
    FAILED,
    /**
     * A capability indexing needs is suspended (its provider configuration was refused): the item
     * is untouched and the sweep stops (docs/adr/0018-pipeline-reliability.md).
     */
    SUSPENDED
  }

  static IndexReconciliationReport indexed(UUID rawInformationItemId, int passages) {
    return new IndexReconciliationReport(
        rawInformationItemId, Outcome.INDEXED, passages + " passage(s)");
  }

  static IndexReconciliationReport skipped(UUID rawInformationItemId, String reason) {
    return new IndexReconciliationReport(rawInformationItemId, Outcome.SKIPPED, reason);
  }

  static IndexReconciliationReport failed(UUID rawInformationItemId, String reason) {
    return new IndexReconciliationReport(rawInformationItemId, Outcome.FAILED, reason);
  }

  static IndexReconciliationReport suspended(UUID rawInformationItemId, String reason) {
    return new IndexReconciliationReport(rawInformationItemId, Outcome.SUSPENDED, reason);
  }
}
