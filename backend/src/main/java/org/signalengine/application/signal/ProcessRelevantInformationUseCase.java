package org.signalengine.application.signal;

import java.util.List;
import java.util.UUID;

/**
 * Input port: drive one {@link org.signalengine.domain.RelevantInformation} record through the
 * relevance &rarr; importance &rarr; Signal &rarr; Summary pipeline (docs/11-roadmap.md Phase 6;
 * docs/adr/0008-relevance-importance-signal-summary.md).
 *
 * <p>Callable explicitly so a future scheduler, or the near-duplicate grouping step, can drive it.
 * Idempotent: a record whose pipeline is already complete (or that was set aside as noise, or that
 * did not warrant a Signal) is skipped; running twice never creates a second Signal or a second
 * Summary. Each step's business-state transition is conditional on the current state, so concurrent
 * runs cannot double-process.
 */
public interface ProcessRelevantInformationUseCase {

  ProcessingReport process(UUID relevantInformationId);

  /** Process a bounded batch of records still somewhere in this pipeline. */
  List<ProcessingReport> processPending();
}
