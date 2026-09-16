package org.signalengine.application.ingestion;

import java.util.List;
import java.util.UUID;

/**
 * Input port: collect from configured sources and persist the collected content as Raw Information
 * Items (docs/08-ingestion.md Section 5, 23; docs/11-roadmap.md Phase 2 Task 5).
 *
 * <p>Callable explicitly so a future in-process scheduler (cadence is Q9) or a future manual
 * "collect now" trigger (Q8) can invoke it. This task adds no scheduler and no REST endpoint.
 *
 * <p>Guarantees:
 *
 * <ul>
 *   <li>a disabled source is never collected (docs/02-functional-spec.md R7);
 *   <li>collecting the same source item again does not create a second Raw Information Item
 *       (docs/03-technical-spec.md Section 10.4) — the operation is safe to retry;
 *   <li>a collection failure is recorded, never swallowed (docs/08-ingestion.md Section 4, 18);
 *   <li>one source's failure does not stop {@link #collectFromEnabledSources()} from collecting the
 *       others (docs/08-ingestion.md Section 17).
 * </ul>
 */
public interface CollectFromSourceUseCase {

  CollectionReport collectFromSource(UUID sourceId);

  List<CollectionReport> collectFromEnabledSources();
}
