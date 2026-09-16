package org.signalengine.application.ingestion;

import java.util.List;

/**
 * What a {@link SourceCollector} produced for one source: either the collected items, or an
 * explicit failure (docs/08-ingestion.md Section 4 — "reports a collection failure when it occurs,
 * rather than failing silently"; Section 17).
 */
public sealed interface CollectionOutcome {

  /** The collector reached the source and returned zero or more items. */
  record Collected(List<CollectedItem> items) implements CollectionOutcome {}

  /**
   * The collector could not collect from the source. {@code retryable} follows the taxonomy in
   * docs/03-technical-spec.md Section 13.1–13.2 (source unreachable / timeout -> retryable; unsafe
   * URL / unusable content -> not retryable).
   */
  record CollectionFailed(String reason, boolean retryable) implements CollectionOutcome {}
}
